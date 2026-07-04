(ns rag.index.lucene
  "The Lucene core. Everything else in the server goes through the six functions
  below — the HTTP handlers (rag.http) and the walk→extract→chunk pipeline
  (rag.index) never touch Lucene directly.

  ─────────────────────────────────────────────────────────────────────────────
  The component
  ─────────────────────────────────────────────────────────────────────────────
  `open` returns a map holding the long-lived Lucene objects that the rest of the
  server threads back into the other functions and finally into `close`:
    :dir      the FSDirectory
    :analyzer the StandardAnalyzer (shared by writer + query parser)
    :writer   the single IndexWriter (Lucene's invariant: one writer per dir)
    :manager  a SearcherManager for lock-free, refreshable reads
  rag.index opens it lazily on first use and holds the single instance.

  ─────────────────────────────────────────────────────────────────────────────
  Chunk map (input to add-chunks!) — one Lucene document per chunk
  ─────────────────────────────────────────────────────────────────────────────
    {:id        \"<doc_id>:<chunk_idx>\"  ; unique per chunk
     :doc_id    \"<sha1 of absolute path>\" ; stable per source file
     :path      \"/abs/path/to/file.md\"
     :ext       \"md\"                     ; may be nil
     :mtime     1712345678000             ; epoch millis (long)
     :chunk_idx 7                         ; int, position within the source doc
     :text      \"...chunk text...\"
     :content_sha1 \"e3b0c4...\"           ; sha1 of the source text (reindex gate)
     :model_spec \"embeddinggemma@85462619ee72/768d\"} ; embedding-model identity (reindex invalidation)

  Field mapping (docs/rag-project-plan.md §4.1 — the M1 subset):
    id        StringField        stored, indexed (not analyzed)
    doc_id    StringField        stored, indexed
    path      StringField        stored, indexed
    ext       StringField        stored, indexed (only when non-nil)
    mtime     LongPoint (range) + StoredField (retrieval) + NumericDocValues (sort)
    chunk_idx IntPoint (range)  + StoredField (retrieval)
    text      TextField          stored, indexed  ← the only analyzed field; BM25 here
    content_sha1 StoredField      stored only (retrieved by doc_id for reindex checks)
    model_spec   StoredField      stored only (embedding-model identity; reindex invalidation)

  ─────────────────────────────────────────────────────────────────────────────
  Hit map (output of keyword-search) — best first
  ─────────────────────────────────────────────────────────────────────────────
    {:id \"a1b2:7\" :doc_id \"a1b2\" :path \"/abs/path\" :score 12.4
     :text \"...\" :chunk_idx 7 :mtime 1712345678000}
  Keys are serialized to JSON verbatim, so keep them exactly as above.

  Notes:
  - BM25 is Lucene's default similarity, so keyword-search scores are BM25.
  - The query string is escaped before parsing so natural-language queries (with
    `?`, `*`, etc.) match literally instead of being read as wildcard syntax.
  - Deletion/reindex by doc_id is M5 — this assumes a fresh or additive index."
  (:import [org.apache.lucene.store FSDirectory]
           [org.apache.lucene.index IndexWriter IndexWriterConfig MultiTerms Term
            VectorSimilarityFunction]
           [org.apache.lucene.document Document Field$Store StringField TextField
            LongPoint IntPoint StoredField NumericDocValuesField KnnFloatVectorField]
           [org.apache.lucene.analysis.standard StandardAnalyzer]
           [org.apache.lucene.search SearcherManager SearcherFactory KnnFloatVectorQuery
            TermQuery PrefixQuery BooleanQuery$Builder BooleanClause$Occur Query]
           [org.apache.lucene.queryparser.classic QueryParser]))

(defn open
  "Open (creating if absent) the Lucene index rooted at `dir` (a java.io.File).
  Return the component map the other fns receive."
  [dir]
  (let [directory (FSDirectory/open (.toPath dir))
        analyzer  (StandardAnalyzer.)
        writer    (IndexWriter. directory (IndexWriterConfig. analyzer))
        manager   (SearcherManager. writer (SearcherFactory.))]
    {:dir directory :analyzer analyzer :writer writer :manager manager}))

(defn- chunk->doc ^Document [{:keys [id doc_id path ext mtime chunk_idx text embedding content_sha1 model_spec]}]
  (let [doc (Document.)]
    (.add doc (StringField. "id" id Field$Store/YES))
    (.add doc (StringField. "doc_id" doc_id Field$Store/YES))
    (.add doc (StringField. "path" path Field$Store/YES))
    (when ext
      (.add doc (StringField. "ext" ext Field$Store/YES)))
    (.add doc (LongPoint. "mtime" (long-array [mtime])))
    (.add doc (StoredField. "mtime" (long mtime)))
    (.add doc (NumericDocValuesField. "mtime" (long mtime)))
    (.add doc (IntPoint. "chunk_idx" (int-array [chunk_idx])))
    (.add doc (StoredField. "chunk_idx" (int chunk_idx)))
    (.add doc (TextField. "text" text Field$Store/YES))
    ;; content_sha1 lets a reindex detect an unchanged file and skip re-embedding
    ;; (rag.index/file->chunks). Stored only — looked up by the indexed doc_id.
    (when content_sha1
      (.add doc (StoredField. "content_sha1" ^String content_sha1)))
    ;; model_spec records the embedding-model identity (name@digest/dim) behind
    ;; :embedding, so a reindex can invalidate stale vectors after a model change
    ;; instead of skipping the file as unchanged (rag.index/file->chunks). Stored only.
    (when model_spec
      (.add doc (StoredField. "model_spec" ^String model_spec)))
    ;; embedding is optional: keyword-only indexing (pre-M3, or if the embedder is
    ;; unavailable) simply omits it. The field's dimension is inferred from the
    ;; float-array length and must be consistent across all docs in the field.
    (when embedding
      (.add doc (KnnFloatVectorField. "embedding" ^floats embedding VectorSimilarityFunction/COSINE)))
    doc))

(defn add-chunks!
  "Add a seq of chunk maps to the index, one document per chunk. Idempotent per
  source file: chunks are grouped by :doc_id and each group atomically *replaces*
  any existing documents with that doc_id (via updateDocuments), so re-indexing a
  file swaps in its current chunks instead of stacking duplicates on the old ones.
  Does not commit — rag.index calls `commit!` once per /index batch."
  [{:keys [^IndexWriter writer]} chunks]
  (doseq [[doc-id group] (group-by :doc_id chunks)]
    (.updateDocuments writer (Term. "doc_id" ^String doc-id) (map chunk->doc group))))

(defn commit!
  "Flush pending additions so they become searchable and durable, then refresh
  readers."
  [{:keys [^IndexWriter writer ^SearcherManager manager]}]
  (.commit writer)
  (.maybeRefreshBlocking manager))

(defn- ^long field-long [^Document doc ^String name]
  (.longValue (.numericValue (.getField doc name))))

(defn- ^long field-int [^Document doc ^String name]
  (.intValue (.numericValue (.getField doc name))))

(defn- ->hit
  "Build a hit map from one ScoreDoc, pulling stored fields via `stored`."
  [stored ^org.apache.lucene.search.ScoreDoc sd]
  (let [doc (.document stored (.doc sd))]
    {:id        (.get doc "id")
     :doc_id    (.get doc "doc_id")
     :path      (.get doc "path")
     :score     (.score sd)
     :text      (.get doc "text")
     :chunk_idx (field-int doc "chunk_idx")
     :mtime     (field-long doc "mtime")}))

(defn- run-search
  "Acquire a searcher, run `query` for the top `k`, map results to hit maps, and
  always release the searcher. Shared by keyword- and vector-search."
  [^SearcherManager manager query k]
  (let [searcher (.acquire manager)]
    (try
      (let [top    (.search searcher query (int k))
            stored (.storedFields searcher)]
        (mapv #(->hit stored %) (.scoreDocs top)))
      (finally
        (.release manager searcher)))))

(defn- ext-filter
  "OR of exact-match term filters over the `ext` field (a chunk matches if its
  extension is any of `exts`)."
  ^Query [exts]
  (let [b (BooleanQuery$Builder.)]
    (doseq [e exts]
      (.add b (TermQuery. (Term. "ext" ^String e)) BooleanClause$Occur/SHOULD))
    (.build b)))

(defn filter-query
  "Build a non-scoring Lucene filter Query from a normalised filter spec, or nil
  if the spec contributes no constraint. Recognised keys (all optional, AND'd):

    :ext          seq of file extensions        → chunk's `ext` is one of them
    :path_prefix  string                        → chunk's `path` starts with it
    :mtime_after  epoch-millis long (inclusive) → chunk's `mtime` >= it

  The result is meant to be used as a FILTER clause (BM25) or as the pre-filter
  argument to the kNN query (vector), so it never affects scoring — it only
  restricts the candidate set. rag.index normalises the request's raw `filters`
  map into this spec (see rag.index/->filter-spec) before calling."
  ^Query [{:keys [ext path_prefix mtime_after]}]
  (let [clauses (cond-> []
                  (seq ext)   (conj (ext-filter ext))
                  path_prefix (conj (PrefixQuery. (Term. "path" ^String path_prefix)))
                  mtime_after (conj (LongPoint/newRangeQuery
                                     "mtime" (long mtime_after) Long/MAX_VALUE)))]
    (when (seq clauses)
      (let [b (BooleanQuery$Builder.)]
        (doseq [c clauses]
          (.add b ^Query c BooleanClause$Occur/FILTER))
        (.build b)))))

(defn keyword-search
  "BM25 search over the `text` field for query string `q`; return up to `k` hit
  maps, best first. `filter` is an optional non-scoring Query (from filter-query,
  or nil) applied as a FILTER clause to restrict the candidate set."
  [{:keys [^StandardAnalyzer analyzer ^SearcherManager manager]} q k filter]
  (let [parser (QueryParser. "text" analyzer)
        text-q (.parse parser (QueryParser/escape q))
        query  (if filter
                 (-> (BooleanQuery$Builder.)
                     (.add ^Query text-q BooleanClause$Occur/MUST)
                     (.add ^Query filter BooleanClause$Occur/FILTER)
                     (.build))
                 text-q)]
    (run-search manager query k)))

(defn vector-search
  "Approximate-nearest-neighbour (HNSW) search over the `embedding` field for the
  query vector `qvec` (a float-array); return up to `k` hit maps, best first. Same
  hit shape as keyword-search, with :score the cosine vector similarity. `filter`
  is an optional Query (from filter-query, or nil) used as the kNN pre-filter, so
  only chunks matching it are considered as neighbours."
  [{:keys [^SearcherManager manager]} ^floats qvec k filter]
  (run-search manager
              (if filter
                (KnnFloatVectorQuery. "embedding" qvec (int k) ^Query filter)
                (KnnFloatVectorQuery. "embedding" qvec (int k)))
              k))

(defn doc-meta
  "Return {:mtime <long> :content_sha1 <string> :model_spec <string>} pulled from
  any one live chunk of `doc-id`, or nil if the doc isn't indexed (:model_spec is
  nil for chunks written before model tracking). Thread-safe (acquires its own
  searcher), so reindex change-detection can run it from worker threads while the
  writer is mid-batch — it sees the last committed state, which is exactly what
  the check wants to compare against."
  [{:keys [^SearcherManager manager]} doc-id]
  (let [searcher (.acquire manager)]
    (try
      (let [top (.search searcher (TermQuery. (Term. "doc_id" ^String doc-id)) (int 1))
            sds (.scoreDocs top)]
        (when (pos? (alength sds))
          (let [doc (.document (.storedFields searcher) (.doc (aget sds 0)))]
            {:mtime        (field-long doc "mtime")
             :content_sha1 (.get doc "content_sha1")
             :model_spec   (.get doc "model_spec")})))
      (finally
        (.release manager searcher)))))

(defn- distinct-terms
  "Count distinct indexed values of `field` across the reader."
  ^long [reader ^String field]
  (if-let [terms (MultiTerms/getTerms reader field)]
    (let [it (.iterator terms)]
      (loop [n 0]
        (if (nil? (.next it)) n (recur (inc n)))))
    0))

(defn stats
  "Return {:num_docs <distinct doc_id count> :num_chunks <live doc count>}."
  [{:keys [^SearcherManager manager]}]
  (let [searcher (.acquire manager)]
    (try
      (let [reader (.getIndexReader searcher)]
        {:num_docs   (distinct-terms reader "doc_id")
         :num_chunks (.numDocs reader)})
      (finally
        (.release manager searcher)))))

(defn close
  "Commit and release everything (manager, writer, directory). Called on server
  shutdown."
  [{:keys [^SearcherManager manager ^IndexWriter writer dir]}]
  (.close manager)
  (.close writer)
  (.close ^java.io.Closeable dir))
