(ns rag.index.chunker
  "Sentence packer: group an ordered seq of sentences (from rag.index.segmenter)
  into overlapping chunks for indexing — the `sentences → chunks` step of the
  pipeline (plan §3.6).

  Strategy (size-free): slide over the sentences in windows of `core` sentences,
  and pad each window with `context` sentence(s) on each side so neighbouring
  chunks overlap. No token counting.

  The output shape is the contract everything downstream depends on
  (rag.index → rag.index.lucene): a vector of {:chunk_idx :text} maps."
  (:require [clojure.string :as str]))

(def default-opts
  "core:    how many fresh sentences each chunk advances by.
   context: overlap sentences added on each side.
   So a chunk holds up to (context + core + context) sentences."
  {:core 3 :context 1})

(defn pack
  "Group `sentences` into overlapping chunks. Returns a vector of
  {:chunk_idx i :text s} maps in order, where :chunk_idx is the 0-based position
  and :text is the chunk's sentences joined by a single space.

  Each chunk covers `core` consecutive sentences plus `context` sentence(s) of
  overlap on each side, clamped at the document's start/end. Blank sentences are
  dropped; nil/empty input → []. A document of ≤ core sentences is a single chunk.

  Downstream (rag.index/file->chunks) calls `(pack (segmenter/segment text))`."
  ([sentences] (pack sentences default-opts))
  ([sentences {:keys [core context]}]
   (let [sentences (vec (remove str/blank? sentences))
         n         (count sentences)]
     (vec
      (map-indexed
       (fn [idx start]
         (let [from (max 0 (- start context))
               to   (min n (+ start core context))]
           {:chunk_idx idx
            :text      (str/join " " (subvec sentences from to))}))
       (range 0 n core))))))
