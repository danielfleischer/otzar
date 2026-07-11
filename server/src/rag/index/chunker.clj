(ns rag.index.chunker
  "Sentence packer: group an ordered seq of sentences (from rag.index.segmenter)
  into overlapping chunks for indexing — the `sentences → chunks` step of the
  pipeline (plan §3.6).

  Strategy (size-free): slide over the sentences in windows of `core` sentences,
  and pad each window with `context` sentence(s) on each side so neighbouring
  chunks overlap. No token counting.

  The output shape is the contract everything downstream depends on
  (rag.index → rag.index.lucene): a vector of {:chunk_idx :text :line_start
  :line_end} maps."
  (:require [clojure.string :as str]))

(def default-opts
  "core:    how many fresh sentences each chunk advances by.
   context: overlap sentences added on each side.
   So a chunk holds up to (context + core + context) sentences."
  {:core 6 :context 2})

(defn pack
  "[{:text str, :line int}], {:core int, :context int}?
     -> [{:chunk_idx int, :text str, :line_start int, :line_end int}]
  Group `sentences` (the maps segmenter/segment returns) into overlapping chunks.
  Returns a vector of {:chunk_idx i :text s :line_start a :line_end b} maps in
  order, where :chunk_idx is the 0-based position, :text is the chunk's sentences
  joined by a single space, and [:line_start :line_end] is the inclusive 1-based
  source-line span covered by the chunk (its first sentence's line through its
  last's — sentence lines are monotonically non-decreasing, so this is the range).

  Each chunk covers `core` consecutive sentences plus `context` sentence(s) of
  overlap on each side, clamped at the document's start/end. Blank sentences are
  dropped; nil/empty input → []. A document of ≤ core sentences is a single chunk.

  Downstream (rag.index/file->chunks) calls `(pack (segmenter/segment text))`."
  ([sentences] (pack sentences default-opts))
  ([sentences {:keys [core context]}]
   (let [sentences (vec (remove #(str/blank? (:text %)) sentences))
         n         (count sentences)]
     (vec
      (map-indexed
       (fn [idx start]
         (let [from   (max 0 (- start context))
               to     (min n (+ start core context))
               window (subvec sentences from to)]
           {:chunk_idx  idx
            :text       (str/join " " (map :text window))
            :line_start (:line (first window))
            :line_end   (:line (peek window))}))
       (range 0 n core))))))
