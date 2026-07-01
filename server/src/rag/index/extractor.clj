(ns rag.index.extractor
  "Text extraction per file. v1 (M1) handles plain-text and source files: read
  the bytes as UTF-8 and return them as a String. Files that are empty, larger
  than `max-bytes`, or that look binary (contain a NUL in their first bytes) are
  skipped by returning nil. Richer extractors (PDF/HTML via Tika) are M8."
  (:import [java.nio.file Files]
           [java.nio.charset StandardCharsets]))

(def ^:private max-bytes
  "Skip files larger than this; nothing in a personal text corpus is legitimately
  bigger, and it keeps a stray blob from blowing up the index."
  (* 5 1024 1024))

(defn- probably-binary?
  "Heuristic: a NUL byte within the first 8 KiB means it's not text we want."
  [^bytes bs]
  (let [limit (min (alength bs) 8192)]
    (loop [i 0]
      (cond
        (>= i limit)         false
        (zero? (aget bs i))  true
        :else                (recur (inc i))))))

(defn extract
  "Return the text content of `file` as a String, or nil if it should be skipped
  (empty, too large, unreadable, or binary)."
  [^java.io.File file]
  (let [len (.length file)]
    (when (<= 1 len max-bytes)
      (try
        (let [bs (Files/readAllBytes (.toPath file))]
          (when-not (probably-binary? bs)
            (String. bs StandardCharsets/UTF_8)))
        (catch java.io.IOException _ nil)))))
