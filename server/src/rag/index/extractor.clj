(ns rag.index.extractor
  "Text extraction per file, dispatched by extension through an open registry.

  `formats` is the registry: one map per format, declaring the extensions it
  claims, the function that turns a File into a String, its citable unit, and
  (optionally) its own size ceiling. `extract` looks up a file's extension,
  applies the shared guards, and hands off to the format. Everything downstream of
  here (segmenter, chunker, embed, lucene) is content-agnostic, so a new format is
  a local change.

  To add one (e.g. docx via java.util.zip over word/document.xml):
    1. Write the extraction fn — File -> str | nil — in its own namespace,
       `rag.index.extract.<fmt>`.
    2. Add a map to `formats` claiming its extensions, with the :locator naming
       the unit its text is divided into (see below).
    3. Test it. `walker` picks the extensions up automatically; there is no
       second list to keep in sync.

  ─────────────────────────────────────────────────────────────────────────────
  Locators
  ─────────────────────────────────────────────────────────────────────────────
  A hit is only useful if you can go look at it, and what you cite differs by
  format: a line in a source file, a page in a PDF. So a format also declares
  *what its extracted text is divided into*, and marks the divisions with a
  separator `rag.index.segmenter` splits on:

    :locator \"line\"  units are separated by a line break — plain text and code
    :locator \"page\"  units are separated by a form feed (\\f) — PDF

  The segmenter numbers the units 1-based and tags each sentence with its unit;
  the chunker folds those into the chunk's [:loc_start :loc_end] span, and the
  span rides through Lucene to the client, which renders `file.md:42-47` or
  `file.pdf p.4`. Adding a unit (docx paragraphs, say) means adding the separator
  to `segmenter/unit-separator` as well as the :locator here — those two are the
  whole vocabulary.

  Guards are split deliberately: size and I/O are shared (here), while content
  sniffing belongs to the format. The NUL-byte check that rejects binaries is a
  plain-text concern — a PDF is *supposed* to be binary — so it lives in the text
  extractor, not in the dispatcher."
  (:require [clojure.string :as str]
            [rag.index.extract.pdf :as pdf])
  (:import [java.nio.file Files]
           [java.nio.charset StandardCharsets]))

(def ^:private default-max-bytes
  "Skip files larger than this; nothing in a personal text corpus is legitimately
  bigger, and it keeps a stray blob from blowing up the index. A format that
  needs more room (PDFs run large) overrides it with :max-bytes."
  (* 5 1024 1024))

(defn ext
  "File -> str | nil
  Lower-cased extension of `f` (without the dot), or nil if it has none. This is
  the registry's dispatch key."
  [^java.io.File f]
  (let [n (.getName f)
        i (.lastIndexOf n ".")]
    (when (pos? i)
      (str/lower-case (subs n (inc i))))))

(defn- probably-binary?
  "Heuristic: a NUL byte within the first 8 KiB means it's not text we want."
  [^bytes bs]
  (let [limit (min (alength bs) 8192)]
    (loop [i 0]
      (cond
        (>= i limit)         false
        (zero? (aget bs i))  true
        :else                (recur (inc i))))))

(defn- extract-text
  "File -> str | nil
  Plain-text and source files: read the bytes as UTF-8. Returns nil for content
  that sniffs as binary despite a text-like extension."
  [^java.io.File file]
  (let [bs (Files/readAllBytes (.toPath file))]
    (when-not (probably-binary? bs)
      (String. bs StandardCharsets/UTF_8))))

(def formats
  "The extraction registry — see the namespace docstring for how to extend it.

  Each entry:
    :name       keyword, for humans
    :extensions set of lower-cased extensions it claims (no dots)
    :extract    File -> str | nil
    :locator    \"line\" | \"page\" — the unit its text is divided into
    :max-bytes  optional size ceiling, defaulting to `default-max-bytes`"
  [{:name       :text
    :locator    "line"
    :extensions #{"txt" "text" "md" "markdown" "org" "rst" "adoc" "tex"
                  "clj" "cljc" "cljs" "cljd" "edn" "bb"
                  "go" "py" "rs" "java" "kt" "kts" "scala"
                  "js" "jsx" "ts" "tsx" "mjs" "cjs"
                  "c" "h" "cc" "cpp" "hpp" "cxx" "hxx"
                  "rb" "php" "pl" "sh" "bash" "zsh" "fish" "lua" "el" "vim"
                  "json" "yaml" "yml" "toml" "ini" "cfg" "conf" "properties"
                  "html" "htm" "css" "scss" "less" "sql" "xml" "csv" "tsv"}
    :extract    extract-text}

   {:name       :pdf
    :locator    "page"
    :extensions #{"pdf"}
    :extract    pdf/extract
    ;; PDFs are the one format where a legitimate document routinely runs past the
    ;; 5 MB text ceiling — a scanned-and-OCR'd book or a figure-heavy paper is
    ;; mostly images we never look at. PDFBox streams from the file rather than
    ;; slurping it, so the ceiling bounds what we're willing to *parse*, not what
    ;; we hold in memory.
    :max-bytes  (* 100 1024 1024)}])

(def ^:private by-ext
  "extension -> format entry. Built once from `formats`; a duplicate claim is a
  bug in the registry, so the later entry silently winning would be worse than
  noticing here."
  (reduce (fn [m {:keys [extensions] :as fmt}]
            (reduce (fn [m e]
                      (when-let [prior (get m e)]
                        (throw (ex-info (str "two formats claim ." e)
                                        {:ext e :formats [(:name prior) (:name fmt)]})))
                      (assoc m e fmt))
                    m extensions))
          {} formats))

(def supported-extensions
  "Every extension the registry can extract — what `walker` filters on."
  (set (keys by-ext)))

(defn locator
  "File -> str
  The unit `file`'s extracted text is divided into, and so the unit a chunk of it
  cites: \"line\" for text and code, \"page\" for PDF (see the namespace docstring).
  rag.index passes it to the segmenter, which splits on that unit's separator, and
  stores it on every chunk so a hit says which kind of span its [:loc_start
  :loc_end] is. Defaults to \"line\" for an unclaimed extension — nothing will be
  chunked for one anyway, since `extract` returns nil."
  [^java.io.File f]
  (:locator (get by-ext (ext f)) "line"))

(defn extract
  "File -> str | nil
  Return the text content of `file` as a String, or nil if it should be skipped:
  no registered format for its extension, empty, over that format's size ceiling,
  unreadable, or rejected by the format itself (e.g. binary content under a
  text-like extension, or a scanned PDF with no text layer).

  The returned text is divided into the format's locator units — see `locator`
  and the namespace docstring — which is what makes a chunk citable.

  Format-specific failure modes beyond I/O (a corrupt or encrypted document) are
  the format's to catch; anything that escapes is caught per-file by
  rag.index/embed-file-result and reported as that file's error. A format that
  wants a failure *reported* rather than silently skipped must therefore throw
  something other than an IOException, which is swallowed here (see
  rag.index.extract.pdf, where a password-protected file raises an ex-info)."
  [^java.io.File file]
  (when-let [fmt (get by-ext (ext file))]
    (let [len (.length file)]
      (when (<= 1 len (:max-bytes fmt default-max-bytes))
        (try
          ((:extract fmt) file)
          (catch java.io.IOException _ nil))))))
