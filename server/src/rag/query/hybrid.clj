(ns rag.query.hybrid
  "Hybrid retrieval — Reciprocal Rank Fusion (RRF) of the keyword (BM25) and
  vector (kNN) rankings into a single ordering.

  This is the M4 core and is intentionally left as a stub with a precise
  contract: rag.index/search already runs both retrievers (each with `k_each`
  candidates and the same metadata filter) and hands their result lists to
  `fuse`. Implementing `fuse` below is the only thing standing between
  `mode=hybrid` and a working end-to-end hybrid query.")

(def ^:const rrf-k
  "RRF damping constant. Larger values flatten the contribution of top ranks
  (the canonical value is 60; see design §6). Tune here."
  40)

(def ^:const vector-weight
  "Vector scores weight."
  1.3)

(defn fuse
  "keyword-hits:[hit], vector-hits:[hit], k:int -> [hit]
  Fuse two ranked hit lists into one via Reciprocal Rank Fusion.

  Inputs
    `keyword-hits`, `vector-hits` — seqs of hit maps, each already ordered
    best-first by its retriever. A hit map has the shape produced by
    rag.index.lucene (see its docstring):
      {:id \"a1b2:7\" :doc_id \"a1b2\" :path \"/abs/path\" :score <raw>
       :text \"...\" :chunk_idx 7 :mtime 1712345678000}
    The same chunk may appear in both lists; it is the *same* chunk iff its
    :id is equal. Either list may be empty.
    `k` — the number of fused hits to return.

  RRF ignores the raw :score values and ranks purely by position:
      rrf(d) = Σ_i  1 / (rrf-k + rank_i(d))
  summed over the lists in which d appears, where rank_i(d) is d's 0-based
  index in list i.

  Output
    The top `k` chunks as hit maps, ordered best-first (highest rrf first),
    each carrying the chunk's stored fields (:id :doc_id :path :text :chunk_idx
    :mtime — take them from whichever list supplied the chunk; the two copies
    are identical) plus:
      :score            the fused RRF score (a small positive double),
      :score_breakdown  {:keyword <bm25 :score, or nil if absent from that list>
                         :vector  <cosine :score, or nil if absent>}

    Ties on the fused score may be broken arbitrarily (stable is nice).

  See docs/design.md §6 for the design rationale."
  [keyword-hits vector-hits k]
  (let [collect (fn [search-type weight {:as item :keys [id score]} idx]
                  (let [body (merge item {:score_breakdown (merge {:keyword nil :vector nil} {search-type score})
                                          :score (* weight (/ (+ rrf-k idx)))})]
                    {(keyword (str id)) body}))
        keyword-hits (map (partial collect :keyword 1) keyword-hits (range))
        vector-hits  (map (partial collect :vector vector-weight) vector-hits (range))
        merge-rrf (fn [f l]
                    (let [breakdown-map (apply merge-with #(or %1 %2) (map :score_breakdown [f l]))
                          score (apply + (map :score [f l]))]
                      (merge f l {:score score :score_breakdown breakdown-map})))
        results (apply merge-with merge-rrf (into keyword-hits vector-hits))
        results (map (fn [[k v]] (merge {:id (name k)} v)) results)]
    (take k (reverse (sort-by :score results)))))

