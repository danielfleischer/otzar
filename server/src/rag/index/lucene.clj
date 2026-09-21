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
     :locator   \"line\"                    ; what loc_start/loc_end count: \"line\" | \"page\"
     :loc_start 42                        ; int, 1-based first unit of the chunk (may be nil)
     :loc_end   47                        ; int, 1-based last unit (may be nil)
     :text      \"...chunk text...\"
     :content_sha1 \"e3b0c4...\"           ; sha1 of the source text (reindex gate)
     :model_spec \"bge-m3@85462619ee72/1024d\"} ; embedding-model identity (reindex invalidation)

  Field mapping (docs/design.md §4.1 — everything but the reserved `tags`):
    id        StringField        stored, indexed (not analyzed)
    doc_id    StringField        stored, indexed
    path      StringField        stored, indexed
    ext       StringField        stored, indexed (only when non-nil)
    mtime     LongPoint (range) + StoredField (retrieval) + NumericDocValues (sort)
    chunk_idx IntPoint (range)  + StoredField (retrieval)
    locator   StoredField       stored only (which unit the span below counts)
    loc_start/loc_end   StoredField  stored only (the chunk's 1-based span in those units,
                                     for `file.md:42-47` / `file.pdf p.4` citation; only
                                     when non-nil — chunks indexed before locators omit them)
    text      TextField          stored, indexed  ← the only analyzed field; BM25 here
    content_sha1 StoredField      stored only (retrieved by doc_id for reindex checks)
    model_spec   StoredField      stored only (embedding-model identity; reindex invalidation)

  ─────────────────────────────────────────────────────────────────────────────
  Hit map (output of keyword-search) — best first
  ─────────────────────────────────────────────────────────────────────────────
    {:id \"a1b2:7\" :doc_id \"a1b2\" :path \"/abs/path\" :score 12.4
     :text \"...\" :chunk_idx 7 :mtime 1712345678000
     :locator \"line\" :loc_start 42 :loc_end 47}
  Keys are serialized to JSON verbatim, so keep them exactly as above.
  :locator/:loc_start/:loc_end are present only for chunks indexed with locator
  tracking (a reindex adds them); they are omitted — not null — for older chunks,
  so a client never has to distinguish \"line 0\" from \"no idea\".

  Notes:
  - BM25 is Lucene's default similarity, so keyword-search scores are BM25.
  - The query string is escaped before parsing so natural-language queries (with
    `?`, `*`, etc.) match literally instead of being read as wildcard syntax.
  - Reindex-by-doc_id works: add-chunks! replaces a file's chunks atomically
    (updateDocuments keyed on doc_id). Standalone deletion is delete-by-paths!,
    which the prune path uses to drop chunks for source files removed/renamed on
    disk (todo.org #8); reclaim-deletes! then expunges them so the term-dictionary
    reads (paths, stats) stop counting the removed files. clear! wipes the whole
    index in one call (deleteAll) — the full reset behind `otzar admin clear`."
  (:import [org.apache.lucene.store FSDirectory]
           [org.apache.lucene.index IndexWriter IndexWriterConfig LeafReaderContext
            MultiTerms Term TieredMergePolicy VectorSimilarityFunction]
           [org.apache.lucene.document Document Field$Store StringField TextField
            LongPoint IntPoint StoredField NumericDocValuesField KnnFloatVectorField]
           [org.apache.lucene.analysis.standard StandardAnalyzer]
           [org.apache.lucene.search SearcherManager SearcherFactory KnnFloatVectorQuery
            TermQuery PrefixQuery BooleanQuery$Builder BooleanClause$Occur Query]
           [org.apache.lucene.queryparser.classic QueryParser]))

(defn open
  "File -> component
  Open (creating if absent) the Lucene index rooted at `dir` (a java.io.File).
  Return the component map the other fns receive.

  The merge policy is the default TieredMergePolicy with one setting changed:
  forceMergeDeletesPctAllowed 0, down from 10. That percentage is the threshold
  forceMergeDeletes (reclaim-deletes!) applies per segment — with the default, a
  segment whose deleted docs are under 10% of it is left alone, so a single pruned
  file's `path` term survives in the term dictionary and `paths` keeps reporting
  the file as indexed. It then looks like an orphan to the *next* prune, which
  reports removing it again, every sweep, forever. At 0 every segment carrying
  deletes is rewritten, so a prune actually takes effect."
  [dir]
  (let [directory (FSDirectory/open (.toPath dir))
        analyzer  (StandardAnalyzer.)
        config    (doto (IndexWriterConfig. analyzer)
                    (.setMergePolicy (doto (TieredMergePolicy.)
                                       (.setForceMergeDeletesPctAllowed 0.0))))
        writer    (IndexWriter. directory config)
        manager   (SearcherManager. writer (SearcherFactory.))]
    {:dir directory :analyzer analyzer :writer writer :manager manager}))

(defn- chunk->doc ^Document [{:keys [id doc_id path ext mtime chunk_idx locator loc_start loc_end text embedding content_sha1 model_spec]}]
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
    ;; loc_start/loc_end give the chunk's 1-based span for citation, and `locator`
    ;; says what that span counts — source lines for text, pages for a PDF
    ;; (rag.index.extractor's registry decides, rag.index.chunker computes). Stored
    ;; only — never queried, just returned on a hit. Nil for chunks indexed before
    ;; locator tracking, so we add them only when present.
    (when locator
      (.add doc (StoredField. "locator" ^String locator)))
    (when loc_start
      (.add doc (StoredField. "loc_start" (int loc_start))))
    (when loc_end
      (.add doc (StoredField. "loc_end" (int loc_end))))
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
  "component, [chunk] -> nil
  Add a seq of chunk maps to the index, one document per chunk. Idempotent per
  source file: chunks are grouped by :doc_id and each group atomically *replaces*
  any existing documents with that doc_id (via updateDocuments), so re-indexing a
  file swaps in its current chunks instead of stacking duplicates on the old ones.
  Does not commit — rag.index calls `commit!` once per /index batch."
  [{:keys [^IndexWriter writer]} chunks]
  (doseq [[doc-id group] (group-by :doc_id chunks)]
    (.updateDocuments writer (Term. "doc_id" ^String doc-id) (map chunk->doc group))))

(defn delete-by-paths!
  "component, paths:[str] -> nil
  Delete every chunk whose stored `path` is in `paths` — orphan cleanup for source
  files removed or renamed on disk. One deleteDocuments call removes docs matching
  ANY of the path terms. Does not commit and does not reclaim the deleted docs (see
  reclaim-deletes!); no-op on an empty seq."
  [{:keys [^IndexWriter writer]} paths]
  (when (seq paths)
    (.deleteDocuments writer
                      ^"[Lorg.apache.lucene.index.Term;"
                      (into-array Term (map #(Term. "path" ^String %) paths)))))

(defn reclaim-deletes!
  "component -> nil
  Physically expunge documents already marked deleted, then commit and refresh.
  Needed after delete-by-paths! because `paths` and stats/:num_docs read the term
  dictionary, which keeps a deleted doc's terms until its segment is rewritten —
  so without this a pruned file would linger in list_indexed_paths until the next
  natural merge. forceMergeDeletes rewrites every segment that has deletes *only*
  because `open` sets forceMergeDeletesPctAllowed to 0 — at Lucene's default of 10%
  a lightly-deleted segment is skipped and the prune never sticks (see `open`).
  Rewriting whole segments is an occasional-maintenance cost (the prune path), not
  something for a hot path.

  The leading commit is not redundant. delete-by-paths! only *buffers* its deletes,
  and the segment-eligibility check inside forceMergeDeletes reads each segment's
  applied delete count — buffered deletes are invisible to it, so the segment scores
  as clean and is skipped, expunging nothing. (Deleting a single document happened
  to survive that: the one case the prune test covered, which is why this held
  together for two months while a multi-file prune silently left every path it had
  just dropped in the term dictionary, to be reported as an orphan again on the next
  sweep, forever.) Committing first applies them, so the merge sees what it is meant
  to reclaim."
  [{:keys [^IndexWriter writer ^SearcherManager manager]}]
  (.commit writer)
  (.forceMergeDeletes writer true)
  (.commit writer)
  (.maybeRefreshBlocking manager))

(defn clear!
  "component -> nil
  Delete every document in the index, then commit and refresh so the wipe is
  durable and paths/stats immediately read empty. Unlike delete-by-paths! (which
  marks selected docs deleted), deleteAll drops *all* segments — which also clears
  the per-field state their FieldInfos carry, including the `embedding` field's
  vector dimension. That makes this the supported reset for an embedding-model
  *dimension* change, which a live index can't otherwise accept (a
  KnnFloatVectorField's dimension is fixed once the field exists): clear, then
  re-index at the new dimension."
  [{:keys [^IndexWriter writer ^SearcherManager manager]}]
  (.deleteAll writer)
  (.commit writer)
  (.maybeRefreshBlocking manager))

(defn commit!
  "component -> nil
  Flush pending additions so they become searchable and durable, then refresh
  readers."
  [{:keys [^IndexWriter writer ^SearcherManager manager]}]
  (.commit writer)
  (.maybeRefreshBlocking manager))

(defn- ^long field-long [^Document doc ^String name]
  (.longValue (.numericValue (.getField doc name))))

(defn- ^long field-int [^Document doc ^String name]
  (.intValue (.numericValue (.getField doc name))))

(defn- field-int? [^Document doc ^String name]
  "Read an optional stored int field, or nil when the doc doesn't carry it (e.g.
  loc_start/loc_end on chunks indexed before locator tracking)."
  (when-let [f (.getField doc name)]
    (.intValue (.numericValue f))))

(defn- with-locator
  "Add the citation span — :locator, :loc_start, :loc_end — to `m` from `doc`, each
  only when the chunk actually carries it. Chunks written before locator tracking
  have none, and omitting beats serializing nulls: a client can then render the
  bare path instead of deciding what `\"loc_start\": null` means."
  [m ^Document doc]
  (let [lc (.get doc "locator")
        ls (field-int? doc "loc_start")
        le (field-int? doc "loc_end")]
    (cond-> m
      lc (assoc :locator lc)
      ls (assoc :loc_start ls)
      le (assoc :loc_end le))))

(defn- ->hit
  "Build a hit map from one ScoreDoc, pulling stored fields via `stored`."
  [stored ^org.apache.lucene.search.ScoreDoc sd]
  (let [doc (.document stored (.doc sd))]
    (with-locator {:id        (.get doc "id")
                   :doc_id    (.get doc "doc_id")
                   :path      (.get doc "path")
                   :score     (.score sd)
                   :text      (.get doc "text")
                   :chunk_idx (field-int doc "chunk_idx")
                   :mtime     (field-long doc "mtime")}
                  doc)))

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
  "{:ext [str], :path_prefix str, :mtime_after long} -> Query | nil
  Build a non-scoring Lucene filter Query from a normalised filter spec, or nil
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
  "component, q:str, k:int, filter:Query|nil -> [hit]
  BM25 search over the `text` field for query string `q`; return up to `k` hit
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
  "component, qvec:float-array, k:int, filter:Query|nil -> [hit]
  Approximate-nearest-neighbour (HNSW) search over the `embedding` field for the
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

(defn vector-dim
  "component -> long | nil
  The `embedding` field's vector dimension currently stored in the index, or nil
  when no chunk carries a vector yet (a fresh or keyword-only index). Read from the
  field metadata (FieldInfo) across the reader's leaves — cheap, no documents
  scanned. A KnnFloatVectorField's dimension is fixed once the field exists, so
  rag.index compares this against the configured model's dimension to fail fast on a
  model change instead of writing (or querying with) a mismatched vector."
  [{:keys [^SearcherManager manager]}]
  (let [searcher (.acquire manager)]
    (try
      (some (fn [^LeafReaderContext ctx]
              (when-let [fi (.fieldInfo (.getFieldInfos (.reader ctx)) "embedding")]
                (let [d (.getVectorDimension fi)]
                  (when (pos? d) d))))
            (.leaves (.getIndexReader searcher)))
      (finally
        (.release manager searcher)))))

(defn doc-meta
  "component, doc-id:str -> {:mtime long, :content_sha1 str, :model_spec str} | nil
  Return {:mtime <long> :content_sha1 <string> :model_spec <string>} pulled from
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

(defn get-chunk
  "component, id:str -> {:id :doc_id :path :ext :mtime :chunk_idx :text} | nil
  Fetch the single chunk whose `id` field equals `id` (the \"<doc_id>:<idx>\"
  form), returning its stored fields, or nil if no such chunk is indexed. The
  :text is the stored chunk text (not the source file). Thread-safe (acquires its
  own searcher). Backs GET /document/:id for a chunk id."
  [{:keys [^SearcherManager manager]} id]
  (let [searcher (.acquire manager)]
    (try
      (let [top (.search searcher (TermQuery. (Term. "id" ^String id)) (int 1))
            sds (.scoreDocs top)]
        (when (pos? (alength sds))
          (let [doc (.document (.storedFields searcher) (.doc (aget sds 0)))]
            (with-locator {:id        (.get doc "id")
                           :doc_id    (.get doc "doc_id")
                           :path      (.get doc "path")
                           :ext       (.get doc "ext")
                           :mtime     (field-long doc "mtime")
                           :chunk_idx (field-int doc "chunk_idx")
                           :text      (.get doc "text")}
                          doc))))
      (finally
        (.release manager searcher)))))

(defn doc-summary
  "component, doc-id:str -> {:doc_id :path :ext :mtime :content_sha1 :num_chunks} | nil
  Per-file metadata for `doc-id`, read from any one of its indexed chunks
  (path/ext/mtime/content_sha1 are identical across a file's chunks; :num_chunks
  is how many are live). nil if the doc isn't indexed. Thread-safe. Backs
  GET /document/:id for a doc_id: the facade uses :path to read the file fresh
  from disk and :content_sha1 to detect drift."
  [{:keys [^SearcherManager manager]} doc-id]
  (let [searcher (.acquire manager)]
    (try
      (let [q   (TermQuery. (Term. "doc_id" ^String doc-id))
            top (.search searcher q (int 1))
            sds (.scoreDocs top)]
        (when (pos? (alength sds))
          (let [doc (.document (.storedFields searcher) (.doc (aget sds 0)))]
            {:doc_id       doc-id
             :path         (.get doc "path")
             :ext          (.get doc "ext")
             :mtime        (field-long doc "mtime")
             :content_sha1 (.get doc "content_sha1")
             :num_chunks   (.count searcher q)})))
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
  "component -> {:num_docs long, :num_chunks int}
  Return {:num_docs <distinct doc_id count> :num_chunks <live doc count>}."
  [{:keys [^SearcherManager manager]}]
  (let [searcher (.acquire manager)]
    (try
      (let [reader (.getIndexReader searcher)]
        {:num_docs   (distinct-terms reader "doc_id")
         :num_chunks (.numDocs reader)})
      (finally
        (.release manager searcher)))))

(defn paths
  "component -> [str]
  Sorted distinct source-file paths currently in the index. The `path` field has
  one indexed term per file (shared by all of that file's chunks), so its term
  dictionary *is* the set of indexed files; Lucene yields the terms in byte order,
  which for these plain path strings reads as sorted. Empty when nothing is
  indexed. Thread-safe. Backs GET /paths (MCP list_indexed_paths)."
  [{:keys [^SearcherManager manager]}]
  (let [searcher (.acquire manager)]
    (try
      (if-let [terms (MultiTerms/getTerms (.getIndexReader searcher) "path")]
        (let [it (.iterator terms)]
          (loop [acc (transient [])]
            (let [br (.next it)]
              (if (nil? br)
                (persistent! acc)
                (recur (conj! acc (.utf8ToString ^org.apache.lucene.util.BytesRef br)))))))
        [])
      (finally
        (.release manager searcher)))))

(defn close
  "component -> nil
  Commit and release everything (manager, writer, directory). Called on server
  shutdown."
  [{:keys [^SearcherManager manager ^IndexWriter writer dir]}]
  (.close manager)
  (.close writer)
  (.close ^java.io.Closeable dir))
