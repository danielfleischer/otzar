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
     :text      \"...chunk text...\"}

  Field mapping (docs/rag-project-plan.md §4.1 — the M1 subset):
    id        StringField        stored, indexed (not analyzed)
    doc_id    StringField        stored, indexed
    path      StringField        stored, indexed
    ext       StringField        stored, indexed (only when non-nil)
    mtime     LongPoint (range) + StoredField (retrieval) + NumericDocValues (sort)
    chunk_idx IntPoint (range)  + StoredField (retrieval)
    text      TextField          stored, indexed  ← the only analyzed field; BM25 here

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
           [org.apache.lucene.index IndexWriter IndexWriterConfig MultiTerms Term]
           [org.apache.lucene.document Document Field$Store StringField TextField
            LongPoint IntPoint StoredField NumericDocValuesField]
           [org.apache.lucene.analysis.standard StandardAnalyzer]
           [org.apache.lucene.search SearcherManager SearcherFactory]
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

(defn- chunk->doc ^Document [{:keys [id doc_id path ext mtime chunk_idx text]}]
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

(defn keyword-search
  "BM25 search over the `text` field for query string `q`; return up to `k` hit
  maps, best first. Filters are M4 — ignored for now."
  [{:keys [^StandardAnalyzer analyzer ^SearcherManager manager]} q k]
  (let [parser   (QueryParser. "text" analyzer)
        query    (.parse parser (QueryParser/escape q))
        searcher (.acquire manager)]
    (try
      (let [top    (.search searcher query (int k))
            stored (.storedFields searcher)]
        (mapv (fn [sd]
                (let [doc (.document stored (.doc sd))]
                  {:id        (.get doc "id")
                   :doc_id    (.get doc "doc_id")
                   :path      (.get doc "path")
                   :score     (.score sd)
                   :text      (.get doc "text")
                   :chunk_idx (field-int doc "chunk_idx")
                   :mtime     (field-long doc "mtime")}))
              (.scoreDocs top)))
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
