(ns rag.index.segmenter
  "Sentence segmentation — the `document → sentences` step of the pipeline
  (plan §3.6). The chunker does the next step, `sentences → chunks`.

  Uses the JVM's built-in java.text.BreakIterator with Unicode sentence-boundary
  rules (UAX #29). This is language-independent: it segments Hebrew, English, and
  mixed-script text without a per-language model. We use it instead of OpenNLP
  because OpenNLP ships no Hebrew model (and needs a downloaded model file at all).

  Trade-off: rule-based breaking splits period-abbreviations like \"Dr.\" into
  their own sentence. That barely affects Hebrew — which abbreviates with the
  geresh/gershayim (׳ ״), not periods — and is only a minor chunking imperfection
  in English, which the ~overlap in the packer softens further."
  (:require [clojure.string :as str])
  (:import [java.text BreakIterator]
           [java.util Locale]))

(defn segment
  "Split `text` into a vector of sentence strings, in order. Each sentence is
  trimmed and empty results dropped; blank/nil input yields an empty vector.
  Segmentation uses locale-neutral (ROOT) Unicode sentence rules, so it works
  across scripts."
  [text]
  (if (str/blank? text)
    []
    (let [bi (BreakIterator/getSentenceInstance Locale/ROOT)]
      (.setText bi ^String text)
      (loop [start (.first bi), acc (transient [])]
        (let [end (.next bi)]
          (if (= end BreakIterator/DONE)
            (persistent! acc)
            (let [s (str/trim (subs text start end))]
              (recur end (if (str/blank? s) acc (conj! acc s))))))))))
