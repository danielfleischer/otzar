(ns rag.index
  "Index facade — the thin orchestration layer between the HTTP handlers and the
  Lucene core (rag.index.lucene). It owns the single, lazily-opened index
  component, runs the walk → extract → chunk → add pipeline, and exposes
  search/stats/close. Every Lucene specific lives behind rag.index.lucene; this
  namespace only knows the chunk/hit contract."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rag.config :as config]
            [rag.embed :as embed]
            [rag.index.lucene :as lucene]
            [rag.index.walker :as walker]
            [rag.index.extractor :as extractor]
            [rag.index.segmenter :as segmenter]
            [rag.index.chunker :as chunker]
            [rag.query.hybrid :as hybrid])
  (:import [java.security MessageDigest]
           [java.time Instant LocalDate ZoneOffset]
           [java.util.concurrent Executors Future]))

(defonce ^:private component (atom nil))

(defn- open? [] (some? @component))

(defn ensure!
  "-> component
  Return the open Lucene component, opening it under ~/.myrag/index on first use.
  Thread-safe: concurrent callers race to open, and the loser's value is dropped."
  []
  (or @component
      (do (.mkdirs config/index-dir)
          (swap! component (fn [c] (or c (lucene/open config/index-dir)))))))

(defn- sha1 [^String s]
  (let [md (MessageDigest/getInstance "SHA-1")]
    (->> (.digest md (.getBytes s "UTF-8"))
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str))))

(defn- file->chunks
  "Turn one file into a seq of chunk maps (the shape rag.index.lucene expects), or
  a tag when there is nothing to (re)embed:
    nil          the extractor skipped this file (unsupported type)
    ::unchanged  already indexed with identical content — skip the costly embed.
  Change detection is model-spec → mtime → content-hash. `spec` is the identity of
  the embedding model we'd use now (embed/model-spec); a prior chunk is *reusable*
  only if it was embedded with that same spec — switching MYRAG_EMBED_MODEL (or a
  re-pull that bumps the digest) makes the stored vectors stale, so it forces a
  re-embed even when the bytes are untouched. Given a reusable prior: if the file's
  mtime still matches, it's skipped *without even reading it*; otherwise we read +
  hash and still skip if the content sha1 matches (an mtime bump with no real edit).
  Only genuinely new/changed/re-modelled files reach the embedder, whose vectors are
  attached as :embedding; every chunk also carries :content_sha1 and :model_spec for
  next time. (A change in embedding *dimension* additionally requires a full reindex
  — clear-index — since Lucene stores one vector dimension per field.)
  `cancelled?` is threaded into the embedder so a large file's embedding is
  abandoned promptly (between batches) when the client disconnects."
  [component spec ^java.io.File f cancelled?]
  (let [path      (.getAbsolutePath f)
        doc-id    (sha1 path)
        mtime     (.lastModified f)
        prior     (lucene/doc-meta component doc-id)
        reusable? (and prior (= spec (:model_spec prior)))]
    (if (and reusable? (= mtime (:mtime prior)))
      ::unchanged
      (when-let [text (extractor/extract f)]
        (let [csha (sha1 text)]
          (if (and reusable? (= csha (:content_sha1 prior)))
            ::unchanged
            (let [ext    (walker/ext f)
                  chunks (chunker/pack (segmenter/segment text))
                  vecs   (embed/embed-documents (.getName f) (map :text chunks) cancelled?)]
              (mapv (fn [{:keys [chunk_idx text]} embedding]
                      {:id           (str doc-id ":" chunk_idx)
                       :doc_id       doc-id
                       :path         path
                       :ext          ext
                       :mtime        mtime
                       :chunk_idx    chunk_idx
                       :text         text
                       :content_sha1 csha
                       :model_spec   spec
                       :embedding    embedding})
                    chunks vecs))))))))

(defn- embed-file-result
  "Run the change-check + extract+chunk+embed for one file (the Ollama-bound part
  we want off the writer thread). Never throws — the result is a map tagged with
  the source file so the writer thread can attribute it:
    {:file f :unchanged true}   already indexed, identical content (nothing embedded)
    {:file f :chunks <seq>}     ok; empty/nil chunks means the extractor skipped it
    {:file f :error <msg>}      extract or embed threw (incl. a cancelled embed)"
  [component spec ^java.io.File f cancelled?]
  (try
    (let [r (file->chunks component spec f cancelled?)]
      (if (= ::unchanged r)
        {:file f :unchanged true}
        {:file f :chunks r}))
    (catch Exception e {:file f :error (.getMessage e)})))

(defn index-paths!
  "paths:[str|File], {:recursive bool}, cancelled?:(->bool)? -> {:indexed :chunks :skipped :unchanged :errors [..] :cancelled?}
  Walk `paths`, extract + chunk + embed each file, and add all chunks to the
  index. The change-check + extract+embed work runs through a bounded pool of
  `config/index-concurrency` workers (it is Ollama-bound and parallelizes well);
  results are drained on this thread — in file-submission order — and written
  through the single, thread-safe IndexWriter, with one commit at the end.
  At most `index-concurrency` files are in flight, so memory stays bounded.
  Files whose content is unchanged since the last index are skipped without
  re-embedding (see file->chunks), counted under :unchanged.

  The 3-arg form takes a no-arg `cancelled?` predicate, polled both here (per
  file) and inside the embedder (between batches, via file->chunks). Once it turns
  true the index is *cancelled*: no more files are submitted, in-flight embeds are
  interrupted, the work done so far is committed, and the result comes back with
  :cancelled true. rag.http drives it from a heartbeat write so a disconnected
  client stops the indexing — and, because it reaches into the embed batches, the
  Ollama work in flight is bounded to the current batch rather than a whole file.
  Returns {:indexed <files> :chunks <n> :skipped :unchanged :errors [{:path ..}]}."
  ([paths opts] (index-paths! paths opts (constantly false)))
  ([paths {:keys [recursive] :or {recursive true}} cancelled?]
   (let [idx    (ensure!)
         spec   (embed/model-spec)  ; one Ollama metadata probe per run; stamped on every chunk
         files  (distinct (mapcat #(walker/walk % {:recursive recursive}) paths))
         pool   (Executors/newFixedThreadPool config/index-concurrency)
         submit (fn [^java.io.File f]
                  (.submit pool ^Callable (fn [] (embed-file-result idx spec f cancelled?))))
         finish (fn [indexed chunks skipped unchanged errors cancelled]
                  (lucene/commit! idx)
                  (cond-> {:indexed indexed :chunks chunks :skipped skipped
                           :unchanged unchanged :errors errors}
                    cancelled (assoc :cancelled true)))]
     (try
       (loop [in-flight (into clojure.lang.PersistentQueue/EMPTY
                             (map submit (take config/index-concurrency files)))
              pending   (drop config/index-concurrency files)
              indexed 0, chunks 0, skipped 0, unchanged 0, errors []]
         (if-let [fut (peek in-flight)]
           (let [result (.get ^Future fut)]  ; wait for the oldest file (worker self-cancels if flagged)
             (if (cancelled?)
               ;; client gone: drop this result, stop submitting, interrupt the
               ;; rest, keep what's already committed.
               (do (.shutdownNow pool)
                   (finish indexed chunks skipped unchanged errors true))
               (let [{:keys [^java.io.File file error] unchg :unchanged cs :chunks} result
                     ;; keep the window full: as each result lands, submit the next
                     in-flight (cond-> (pop in-flight)
                                 (seq pending) (conj (submit (first pending))))
                     pending   (if (seq pending) (rest pending) pending)
                     [indexed chunks skipped unchanged errors]
                     (cond
                       error    [indexed chunks skipped unchanged
                                 (conj errors {:path (.getAbsolutePath file) :error error})]
                       unchg    [indexed chunks skipped (inc unchanged) errors]
                       (seq cs) (do (lucene/add-chunks! idx cs)
                                    [(inc indexed) (+ chunks (count cs)) skipped unchanged errors])
                       :else    [indexed chunks (inc skipped) unchanged errors])]
                 (recur in-flight pending indexed chunks skipped unchanged errors))))
           (finish indexed chunks skipped unchanged errors false)))
       (finally (.shutdown pool))))))

(defn- ->epoch-ms
  "Coerce an mtime bound to epoch millis. Accepts a number (already millis) or an
  ISO-8601 string — a full instant (`2025-01-01T00:00:00Z`) or a bare date
  (`2025-01-01`, read as start-of-day UTC)."
  ^Long [v]
  (cond
    (number? v) (long v)
    (string? v) (try
                  (.toEpochMilli (Instant/parse v))
                  (catch Exception _
                    (-> (LocalDate/parse v)
                        (.atStartOfDay ZoneOffset/UTC)
                        (.toInstant)
                        (.toEpochMilli))))
    :else (throw (ex-info "mtime_after must be epoch-millis or an ISO-8601 date/instant"
                          {:type :bad-filter :mtime_after v}))))

(defn- ->filter-spec
  "Normalise a request `filters` map into the typed spec rag.index.lucene expects
  (see lucene/filter-query), dropping absent keys. Returns nil when there is no
  usable constraint, so callers can `some->` it into a filter Query."
  [{:keys [ext path_prefix mtime_after]}]
  (let [exts (cond (nil? ext) nil (coll? ext) (vec ext) :else [ext])
        spec (cond-> {}
               (seq exts)  (assoc :ext (mapv str exts))
               path_prefix (assoc :path_prefix (str path_prefix))
               mtime_after (assoc :mtime_after (->epoch-ms mtime_after)))]
    (not-empty spec)))

(defn search
  "{:q str, :k int?, :mode str?, :filters map?, :fusion map?} -> {:hits [hit]}
  Search the index. Returns {:hits [...]}.

    mode \"keyword\" → BM25 over the text field
    mode \"vector\"  → embed the query and run kNN over the embedding field
    mode \"hybrid\"  → run both (each over `k_each` candidates) and fuse with RRF

  `filters` (optional) narrows every mode via a metadata pre-filter — :ext,
  :path_prefix, :mtime_after (see ->filter-spec). `fusion` (hybrid only) may set
  :k_each, the candidates drawn from each retriever before fusion (default 50)."
  [{:keys [q k mode filters fusion] :or {k 10 mode "hybrid"}}]
  (let [comp   (ensure!)
        filt   (some-> (->filter-spec filters) lucene/filter-query)
        k-each (get fusion :k_each 50)]
    {:hits (case mode
             "keyword" (lucene/keyword-search comp q k filt)
             "vector"  (lucene/vector-search comp (embed/embed-query q) k filt)
             "hybrid"  (hybrid/fuse
                        (lucene/keyword-search comp q k-each filt)
                        (lucene/vector-search comp (embed/embed-query q) k-each filt)
                        k)
             (throw (ex-info (str "mode \"" mode "\" not supported (have: keyword, vector, hybrid)")
                             {:type :unsupported-mode :mode mode})))}))

(defn document
  "id:str -> {..} | nil
  Fetch by id for GET /document/:id. `id` is either a chunk id (\"<doc_id>:<idx>\",
  which contains a colon) or a bare doc_id (a whole file).

  Chunk id → the stored chunk, straight from Lucene:
    {:id :doc_id :path :ext :mtime :chunk_idx :text}

  doc_id → the whole file. We deliberately re-read the source file from disk at its
  indexed :path rather than stitching the stored chunks back together — the chunker
  joins sentences with single spaces and drops blank lines, so reassembled chunk
  text is lossy (wrong whitespace, no original newlines/line numbers). Disk is the
  faithful copy. Returns:
    {:doc_id :path :ext :mtime :num_chunks :text <disk contents> [:stale true]}
  :stale is added when the file's current sha1 differs from what was indexed (it
  changed since indexing). :content_sha1 is not surfaced.

  Returns nil when the id isn't indexed, or (doc_id) the file is no longer readable
  on disk — the handler maps nil to 404."
  [id]
  (let [comp (ensure!)]
    (if (str/includes? id ":")
      (lucene/get-chunk comp id)
      (when-let [{:keys [path content_sha1] :as info} (lucene/doc-summary comp id)]
        (when-let [text (extractor/extract (io/file path))]
          (cond-> (-> info (dissoc :content_sha1) (assoc :text text))
            (not= content_sha1 (sha1 text)) (assoc :stale true)))))))

(defn stats
  "-> {:num_docs int, :num_chunks int}
  Index counts, or zeros if nothing has opened the index yet."
  []
  (if (open?)
    (lucene/stats @component)
    {:num_docs 0 :num_chunks 0}))

(defn close!
  "-> nil
  Close and forget the index component. No-op if never opened."
  []
  (when-let [c @component]
    (lucene/close c)
    (reset! component nil)))
