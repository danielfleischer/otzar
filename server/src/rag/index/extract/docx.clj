(ns rag.index.extract.docx
  "Word (.docx) text extraction — one format in the rag.index.extractor registry.

  A .docx is a zip of XML parts. All the prose lives in one of them,
  `word/document.xml`, as a tree of `<w:p>` paragraphs holding `<w:t>` text runs
  (a run per stretch of uniform formatting, so a single sentence is routinely
  split across several). Extraction is: open the zip, stream that one part, and
  concatenate the runs paragraph by paragraph. No dependency — `java.util.zip`
  and the JDK's StAX parser both ship with the JVM, which is why this is 60 lines
  instead of Apache POI.

  Paragraphs are the citable unit, and the third locator in the vocabulary after
  \"line\" and \"page\". A .docx has no lines — line breaks are a rendering
  result, decided by the page size and font at display time — and no pages for
  the same reason, so neither can be recovered from the file. What it does have,
  explicitly and stably, is a paragraph sequence. Paragraphs are separated in the
  extracted text by U+2029 PARAGRAPH SEPARATOR, which `rag.index.segmenter`
  splits on for the \"paragraph\" locator, and hits cite `notes.docx ¶12`.

  Empty paragraphs are kept, as empty units. They are how a document spaces
  itself, and dropping them would renumber every paragraph after the first blank
  line — the count has to match what someone counts in Word for the citation to
  mean anything.

  Only `word/document.xml` is read. Headers, footers, footnotes, endnotes and
  comments live in sibling parts and are left out: they are page furniture, they
  are not in the paragraph sequence being numbered, and including them would put
  a running header between every pair of paragraphs.

  Not handled: the old binary .doc (an OLE container, not a zip — it fails the
  `word/document.xml` lookup and is skipped) and encrypted files (also OLE, also
  skipped). Both are silent skips rather than reported errors, on the grounds
  that a .doc is a different format rather than a broken .docx."
  (:require [clojure.string :as str])
  (:import [java.util.zip ZipFile]
           [javax.xml.stream XMLInputFactory XMLStreamConstants]))

(def ^:private para-sep
  "U+2029 PARAGRAPH SEPARATOR — the unit boundary the segmenter splits on for the
  \"paragraph\" locator. Chosen for the same reason the PDF extractor writes a
  form feed: it is the character that means exactly this, and Word will never put
  one inside a text run."
  "\u2029")

(defn- ^XMLInputFactory reader-factory
  "A StAX factory with external entity resolution off. document.xml is untrusted
  input from a file we were handed, and a DTD that pulls in an external entity is
  the standard XML attack; we have no use for one either way."
  []
  (doto (XMLInputFactory/newInstance)
    (.setProperty XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES false)
    (.setProperty XMLInputFactory/SUPPORT_DTD false)))

(defn- read-paragraphs
  "InputStream -> [str]
  Stream document.xml, returning one string per `<w:p>` in document order.

  Elements are matched on local name, ignoring the namespace prefix: `w` is
  conventional but not guaranteed, and the local names are unambiguous inside
  this part. Four matter — `p` (paragraph), `t` (a text run's text), `tab`, and
  `br` — and everything else, which is formatting, is skipped by falling through.

  A `<w:br/>` becomes a newline rather than a space: the author pressed
  shift-enter, and the segmenter treats a newline as a hard sentence boundary,
  which is exactly right for the address block or verse that a manual break is
  usually holding together."
  [in]
  (let [rdr (.createXMLStreamReader (reader-factory) in)]
    (loop [paras (transient []), cur nil, in-text? false]
      (if-not (.hasNext rdr)
        (persistent! (cond-> paras cur (conj! (str cur))))
        (do
          (.next rdr)
          (condp = (.getEventType rdr)
            XMLStreamConstants/START_ELEMENT
            (case (.getLocalName rdr)
              "p"   (recur (cond-> paras cur (conj! (str cur))) (StringBuilder.) false)
              "t"   (recur paras cur true)
              "tab" (do (some-> ^StringBuilder cur (.append \tab)) (recur paras cur false))
              "br"  (do (some-> ^StringBuilder cur (.append \newline)) (recur paras cur false))
              (recur paras cur false))

            XMLStreamConstants/CHARACTERS
            (do (when (and in-text? cur)
                  (.append ^StringBuilder cur (.getText rdr)))
                (recur paras cur in-text?))

            XMLStreamConstants/END_ELEMENT
            (recur paras cur (if (= "t" (.getLocalName rdr)) false in-text?))

            (recur paras cur in-text?)))))))

(defn extract
  "File -> str | nil
  Extract `file`'s text as U+2029-separated paragraphs, so a chunk's
  [:loc_start :loc_end] is a paragraph range. Returns nil when the file is not a
  .docx we can read — no `word/document.xml` part (a renamed .doc, an encrypted
  file) or nothing but empty paragraphs — in which case the dispatcher skips it.

  A file that isn't a zip at all raises the ZipException that the dispatcher
  treats as a skip, which is the intended reading of a mislabelled file."
  [^java.io.File file]
  (with-open [zip (ZipFile. file)]
    (when-let [entry (.getEntry zip "word/document.xml")]
      (let [paras (with-open [in (.getInputStream zip entry)]
                    (read-paragraphs in))
            text  (str/join para-sep paras)]
        (when-not (str/blank? text)
          text)))))
