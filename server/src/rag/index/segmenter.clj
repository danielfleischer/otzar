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
  in English, which the ~overlap in the packer softens further.

  Line breaks are hard sentence boundaries: the text is split on every newline
  first (a lone \\n counts), then BreakIterator splits each line on punctuation.
  BreakIterator alone does not break on newlines — so line-oriented text with no
  periods (checklists, bullet lists, notes) would otherwise collapse into a single
  giant \"sentence\", and the packer has no size cap to catch it. This matches how
  such text is written and read: one item per line."
  (:require [clojure.string :as str])
  (:import [java.text BreakIterator]
           [java.util Locale]))

(defn- sentences-in
  "Sentence-split one already-single-line string via `bi` (locale-neutral Unicode
  rules), returning a vector of trimmed, non-blank sentences in order."
  [^BreakIterator bi ^String line]
  (.setText bi line)
  (loop [start (.first bi), acc (transient [])]
    (let [end (.next bi)]
      (if (= end BreakIterator/DONE)
        (persistent! acc)
        (let [s (str/trim (subs line start end))]
          (recur end (if (str/blank? s) acc (conj! acc s))))))))

(defn segment
  "str -> [{:text str, :line int}]
  Split `text` into a vector of sentence maps, in order — each {:text <sentence>
  :line <1-based source line>}. Sentences are trimmed and empty results dropped;
  blank/nil input yields an empty vector.

  Splits on every line break first (a lone newline is a hard boundary), then
  applies locale-neutral (ROOT) Unicode sentence rules within each line — so it
  works across scripts and never lets a run of un-punctuated lines collapse into
  one oversized sentence. A single BreakIterator is reused across lines (safe:
  each line is fully segmented before the next .setText).

  :line is the source line the sentence sits on, defined by that same split on
  `\\R` (so a `\\r\\n` counts once): the Nth `\\R`-delimited line is line N (1-based).
  Every sentence from a line carries that line's number; the chunker folds these
  into a per-chunk [:line_start :line_end] range for `path:line` citation. This is
  faithful only while the extracted text is the file's bytes (plain-text/source
  files) — it does not survive a reconstructing extractor (PDF/HTML)."
  [text]
  (if (str/blank? text)
    []
    (let [bi (BreakIterator/getSentenceInstance Locale/ROOT)]
      (into []
            (mapcat (fn [i line]
                      (map (fn [s] {:text s :line (inc i)}) (sentences-in bi line)))
                    (range)
                    (str/split text #"\R"))))))
