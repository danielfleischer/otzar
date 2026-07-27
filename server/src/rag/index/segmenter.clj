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
  such text is written and read: one item per line.

  Segmentation also assigns each sentence its *locator*: which numbered unit of
  the document it sits in, so a chunk can be cited. The unit depends on the
  format the text came from — a line in a source file, a page in a PDF — and is
  marked in the text by that unit's separator (see `unit-separator`). The
  registry entry in rag.index.extractor decides which; this namespace only knows
  how to split on it."
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

(def unit-separator
  "locator -> the regex separating that locator's units in extracted text. This is
  the other half of the contract in rag.index.extractor's registry: a format
  declares its :locator, and its extracted text must mark unit boundaries with the
  separator named here. Adding a locator (docx paragraphs, say) means an entry in
  both places.

  \"line\"       `\\R` — one unit per source line, which is how a text file arrives
                anyway. HTML keeps this unit by stripping tags in place, without
                ever adding or removing a newline.
  \"page\"       `\\f` — a form feed per page, which rag.index.extract.pdf writes out.
  \"paragraph\"  `\\u2029` — U+2029 PARAGRAPH SEPARATOR between the `<w:p>`s of a
                .docx, which rag.index.extract.docx writes out."
  {"line"      #"\R"
   "page"      #"\f"
   "paragraph" #"\u2029"})

(defn segment
  "str, locator:str? -> [{:text str, :loc int}]
  Split `text` into a vector of sentence maps, in order — each {:text <sentence>
  :loc <1-based unit number>}. Sentences are trimmed and empty results dropped;
  blank/nil input yields an empty vector. `locator` defaults to \"line\"; an
  unknown one throws (it means a format registered a unit nothing can split on).

  Two splits happen, and they are not the same split. The *unit* split — on the
  locator's separator — is what numbers the text for citation. The *line* split
  within each unit is what keeps sentences honest: a lone newline is a hard
  sentence boundary, because BreakIterator alone would let a run of un-punctuated
  lines (a checklist, a bullet list) collapse into one oversized sentence and the
  packer has no size cap to catch it. For \"line\" the two coincide, so each unit
  is already a single line and the inner split is a no-op. A single BreakIterator
  is reused throughout (safe: each line is fully segmented before the next
  .setText).

  :loc is the 1-based ordinal of the unit the sentence sits in — for \"line\", the
  Nth `\\R`-delimited line (so a `\\r\\n` counts once); for \"page\", the Nth page.
  Every sentence carries its unit's number and the chunker folds these into a
  per-chunk [:loc_start :loc_end] range for citation. Line numbers are faithful
  only while the extracted text is the file's bytes — which is exactly why a
  reconstructing extractor (PDF) cites the unit it does still know: the page."
  ([text] (segment text "line"))
  ([text locator]
   (if (str/blank? text)
     []
     (let [bi  (BreakIterator/getSentenceInstance Locale/ROOT)
           sep (or (unit-separator locator)
                   (throw (ex-info (str "no unit separator for locator \"" locator "\"")
                                   {:type :unknown-locator :locator locator})))]
       (into []
             (mapcat (fn [i unit]
                       (map (fn [s] {:text s :loc (inc i)})
                            (mapcat #(sentences-in bi %) (str/split unit #"\R"))))
                     (range)
                     (str/split text sep)))))))
