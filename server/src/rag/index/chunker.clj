(ns rag.index.chunker
  "Sentence packer: group an ordered seq of sentences (from rag.index.segmenter)
  into overlapping chunks for indexing — the `sentences → chunks` step of the
  pipeline (plan §3.6).

  Strategy (size-free): slide over the sentences in windows of `core` sentences,
  and pad each window with `context` sentence(s) on each side so neighbouring
  chunks overlap. No token counting.

  The output shape is the contract everything downstream depends on
  (rag.index → rag.index.lucene): a vector of {:chunk_idx :text :loc_start
  :loc_end} maps. The packer is deliberately incurious about what a :loc *is* —
  the segmenter numbers lines for a text file and pages for a PDF, and either way
  a chunk's span is just its window's first through last."
  (:require [clojure.string :as str]))

(def default-opts
  "core:    how many fresh sentences each chunk advances by.
   context: overlap sentences added on each side.
   So a chunk holds up to (context + core + context) sentences."
  {:core 6 :context 2})

(defn pack
  "[{:text str, :loc int}], {:core int, :context int}?
     -> [{:chunk_idx int, :text str, :loc_start int, :loc_end int}]
  Group `sentences` (the maps segmenter/segment returns) into overlapping chunks.
  Returns a vector of {:chunk_idx i :text s :loc_start a :loc_end b} maps in
  order, where :chunk_idx is the 0-based position, :text is the chunk's sentences
  joined by a single space, and [:loc_start :loc_end] is the inclusive 1-based
  span of locator units covered by the chunk — lines, or pages for a PDF (its
  first sentence's unit through its last's; sentence units are monotonically
  non-decreasing, so this is the range).

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
           {:chunk_idx idx
            :text      (str/join " " (map :text window))
            :loc_start (:loc (first window))
            :loc_end   (:loc (peek window))}))
       (range 0 n core))))))
