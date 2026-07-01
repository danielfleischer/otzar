(ns rag.index.chunker
  "Naive whitespace chunker for M1: split text into whitespace-delimited tokens
  and pack them into fixed-size windows with a small overlap. This is deliberately
  crude — the OpenNLP sentence-aware packer replaces it in M2. The output shape is
  the contract that survives that swap: a vector of {:chunk_idx :text} maps, so
  everything downstream (rag.index → rag.index.lucene) is unaffected by the change.

  Note: because splitting is on runs of whitespace, original spacing/newlines are
  collapsed to single spaces in the chunk text. That's fine for BM25 keyword search
  in M1; M2's segmenter preserves more structure."
  (:require [clojure.string :as str]))

(def default-opts
  "~200 tokens per chunk with ~40 of overlap. Roomier than the ~300/50 target in
  the plan since a whitespace token is smaller than a real subword token; revisit
  once a proper tokenizer lands."
  {:chunk-size 200 :overlap 40})

(defn- windows
  "Vector of subvectors of `tokens`, each up to `size` long, advancing by
  (size - overlap) each step so adjacent windows share `overlap` tokens."
  [tokens size overlap]
  (let [step (max 1 (- size overlap))
        n    (count tokens)]
    (loop [start 0, acc []]
      (if (>= start n)
        acc
        (let [end (min n (+ start size))
              w   (subvec tokens start end)]
          (if (>= end n)
            (conj acc w)
            (recur (+ start step) (conj acc w))))))))

(defn chunk-text
  "Split `text` into chunks. Returns a vector of {:chunk_idx i :text s} maps in
  order; blank/empty input yields an empty vector."
  ([text] (chunk-text text default-opts))
  ([text {:keys [chunk-size overlap]}]
   (let [tokens (into [] (remove str/blank?) (str/split (or text "") #"\s+"))]
     (if (empty? tokens)
       []
       (into []
             (map-indexed (fn [i w] {:chunk_idx i :text (str/join " " w)}))
             (windows tokens chunk-size overlap))))))
