(ns rag.index.extract.pdf
  "PDF text extraction via PDFBox — one format in the rag.index.extractor registry.

  A PDF stores glyphs at coordinates, not a document: there are no source lines
  and often no sentences, just runs of text placed on a page. So extraction here
  is two steps, and the second one matters more than the first:

  1. `PDFTextStripper` turns each page into visual lines (one `\\n` per line as it
     was *typeset*, not as it was written).
  2. `unwrap-page` puts the prose back together — rejoining words hyphenated
     across a line break, folding soft-wrapped lines into one flowing line, and
     leaving list items on their own. Without it the segmenter would see
     \"…a technique that com-\" as a whole sentence and index it that way.

  Pages are separated by a form feed (`\\f`), which is what
  `rag.index.segmenter` splits on for the \"page\" locator — so a PDF chunk's
  [:loc_start :loc_end] span is a *page* range, and hits cite `file.pdf p.4`.
  That is the whole reason the extractor registry carries a :locator: pages are
  the only citable unit a PDF has.

  Failures are deliberately split. A file we simply can't read (truncated,
  corrupt) raises the IOException that the dispatcher turns into a skip; a file
  we could read but are not allowed to (password-protected, extraction denied by
  its permissions) raises an ex-info, so it surfaces as that file's error in the
  index run rather than vanishing into the skipped count."
  (:require [clojure.string :as str])
  (:import [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.pdmodel.encryption InvalidPasswordException]
           [org.apache.pdfbox.text PDFTextStripper]))

(def ^:private list-item
  "A line that opens a list item — a bullet or an enumerator. These are the lines
  `unwrap-page` must *not* fold into their predecessor: in a slide deck or a table
  of contents nothing ends in a period, so joining them would collapse the page
  into one unreadable run."
  #"^(?:[-–—•·*+‣▪◦]|\(?\d{1,3}[.)]|\(?[a-zA-Z][.)])\s")

(def ^:private hyphenated
  "A word broken across the line break: a letter, then a trailing hyphen."
  #"\p{L}-$")

(defn- unwrap-page
  "str -> str
  Rejoin one page's visually-wrapped lines into logical lines. PDFBox emits a
  `\\n` per typeset line, so consecutive lines are usually the *same* sentence
  mid-flow; we join them with a space, dropping the hyphen when the break split a
  word. A blank line (an explicit gap) or a list marker starts a new logical line
  instead. The result is one line per paragraph/list item — which is exactly what
  the segmenter treats as a hard sentence boundary."
  [page]
  (->> (str/split page #"\R")
       (map str/trim)
       (reduce (fn [blocks line]
                 (let [prev (peek blocks)]
                   (cond
                     ;; An explicit gap closes the current block (nil marks the break).
                     (str/blank? line)
                     (cond-> blocks (some? prev) (conj nil))

                     ;; Start of a block, either the first line or just after a gap.
                     (nil? prev)
                     (conj (cond-> blocks (seq blocks) pop) line)

                     ;; A list item owns its line even mid-flow.
                     (re-find list-item line)
                     (conj blocks line)

                     ;; "com-\nbines" was one word before it was typeset.
                     (re-find hyphenated prev)
                     (conj (pop blocks) (str (subs prev 0 (dec (count prev))) line))

                     :else
                     (conj (pop blocks) (str prev " " line)))))
               [])
       (remove nil?)
       (str/join "\n")))

(defn- denied!
  "Refuse `file` with a message the user can act on. Both cases are 'the bytes are
  fine, the document is closed to us', which we want reported per file rather than
  folded into the silent skipped count — hence ex-info (not IOException, which the
  dispatcher treats as a skip)."
  [^java.io.File file why]
  (throw (ex-info (str "cannot extract " (.getName file) ": " why)
                  {:type :pdf-locked :path (.getAbsolutePath file)})))

(defn- open!
  "File -> PDDocument
  Load `file`, translating PDFBox's 'wrong password' — which is an IOException
  subclass, and so would otherwise be swallowed as an unreadable-file skip — into
  a reported error."
  ^PDDocument [^java.io.File file]
  (try
    (Loader/loadPDF file)
    (catch InvalidPasswordException _
      (denied! file "it is password-protected"))))

(defn extract
  "File -> str | nil
  Extract `file`'s text, one form-feed-separated block per page, with each page's
  soft-wrapped lines unwrapped into logical lines (see `unwrap-page`).

  Returns nil when there is no text to index — the common case being a scanned
  PDF, which is an image per page with no text layer (we do not OCR). Throws
  ex-info for a PDF we are not permitted to read (password-protected, or whose
  permissions forbid extraction); IOException, from PDFBox, for one we cannot
  parse at all, which the extractor dispatcher turns into a skip.

  PDFTextStripper is stateful and not thread-safe, so a fresh one is built per
  call — indexing extracts from several worker threads at once."
  [^java.io.File file]
  (with-open [doc (open! file)]
    (when-not (.canExtractContent (.getCurrentAccessPermission doc))
      (denied! file "its permissions forbid text extraction"))
    (let [stripper (doto (PDFTextStripper.)
                     ;; Read in visual order rather than the order the glyphs happen
                     ;; to be written in the content stream — the difference shows up
                     ;; on anything with columns or a header/footer.
                     (.setSortByPosition true)
                     (.setPageEnd "\f"))
          text     (->> (str/split (.getText stripper doc) #"\f")
                        (map unwrap-page)
                        (str/join "\f"))]
      (when-not (str/blank? text)
        text))))
