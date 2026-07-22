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
           [java.util.concurrent ExecutorCompletionService Executors Future]))

(defonce ^:private component (atom nil))

(def ^:private commit-interval-ms
  "Commit the index at least this often during a long run so files that finish
  while a big file is still embedding become durable promptly and survive an
  interrupt. A commit fsyncs, so this trades a little throughput for a small
  worst-case loss window; per-file atomicity is unaffected."
  5000)

(defn- open? [] (some? @component))

(defn ensure!
  "-> component
  Return the open Lucene component, opening it under ~/.myrag/index on first use.
  Thread-safe: concurrent callers race to open, and the loser's value is dropped."
  []
  (or @component
      (do (.mkdirs config/index-dir)
          (swap! component (fn [c] (or c (lucene/open config/index-dir)))))))

(defn- dim-mismatch-message [indexed configured]
  (str "Embedding dimension changed: the index holds " indexed "-dim vectors but "
       "the configured model now produces " configured "-dim. Lucene can't mix "
       "vector dimensions in one field, so switching embedding models needs a full "
       "re-index:\n"
       "  myrag admin clear\n"
       "  myrag index <paths…>"))

(defn- ensure-dim!
  "Fail fast when the index already holds vectors of a different dimension than
  `want` (the dimension the configured model produces now). A KnnFloatVectorField's
  dimension is fixed once the field exists, so a model change to a new dimension
  can't be applied in place — it needs a clear + re-index. Throws an ex-info
  {:type :dimension-mismatch} whose message spells out the commands (the HTTP layer
  maps it to a 400 for /query and an error event for /index; the CLI prints it).
  No-op on an empty/keyword-only index (nothing to conflict with) or when `want` is
  nil (dimension unknown — then a real mismatch still surfaces at the Lucene write,
  just less prettily)."
  [component want]
  (when want
    (when-let [have (lucene/vector-dim component)]
      (when (not= (long have) (long want))
        (throw (ex-info (dim-mismatch-message have want)
                        {:type :dimension-mismatch :indexed have :configured want}))))))

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
  next time. (A change in embedding *dimension* additionally requires a full reset
  — `myrag admin clear`, then re-index — since Lucene stores one vector dimension
  per field.)
  `force?` bypasses all of that: every file is re-extracted, re-chunked and
  re-embedded even when nothing changed. It's the escape hatch for changes the
  content/model gate can't see — a new chunker or a chunk-schema field (e.g. adding
  :line_start/:line_end): the bytes and model are identical, so only an explicit
  force re-populates existing docs.
  `cancelled?` is threaded into the embedder so a large file's embedding is
  abandoned promptly (between batches) when the client disconnects. `on-progress`
  receives this file's progress events: once {:type \"file-start\" :path :total
  <chunk count>} when embedding begins, then {:type \"chunk\" :path :done :total}
  per batch. Emitted from a worker thread, so `on-progress` must be thread-safe."
  [component spec ^java.io.File f cancelled? on-progress force?]
  (let [path      (.getAbsolutePath f)
        doc-id    (sha1 path)
        mtime     (.lastModified f)
        prior     (lucene/doc-meta component doc-id)
        reusable? (and (not force?) prior (= spec (:model_spec prior)))]
    (if (and reusable? (= mtime (:mtime prior)))
      ::unchanged
      (when-let [text (extractor/extract f)]
        (let [csha (sha1 text)]
          (if (and reusable? (= csha (:content_sha1 prior)))
            ::unchanged
            (let [ext      (walker/ext f)
                  chunks   (chunker/pack (segmenter/segment text))
                  _        (when (seq chunks)
                             (on-progress {:type "file-start" :path path :total (count chunks)}))
                  on-batch (fn [done total]
                             (on-progress {:type "chunk" :path path :done done :total total}))
                  vecs     (embed/embed-documents (map :text chunks) cancelled? on-batch)]
              (mapv (fn [{:keys [chunk_idx text line_start line_end]} embedding]
                      {:id           (str doc-id ":" chunk_idx)
                       :doc_id       doc-id
                       :path         path
                       :ext          ext
                       :mtime        mtime
                       :chunk_idx    chunk_idx
                       :line_start   line_start
                       :line_end     line_end
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
  [component spec ^java.io.File f cancelled? on-progress force?]
  (try
    (let [r (file->chunks component spec f cancelled? on-progress force?)]
      (if (= ::unchanged r)
        {:file f :unchanged true}
        {:file f :chunks r}))
    (catch Exception e {:file f :error (.getMessage e)})))

(defn index-paths!
  "paths:[str|File], {:recursive bool}, cancelled?:(->bool)?, on-progress:(fn[event])? -> {:indexed :chunks :skipped :unchanged :errors [..] :cancelled?}
  Walk `paths`, extract + chunk + embed each file, and add all chunks to the
  index. The change-check + extract+embed work runs through a bounded pool of
  `config/index-concurrency` workers (it is Ollama-bound and parallelizes well);
  results are drained on this thread in *completion order* (via an
  ExecutorCompletionService) and written through the single, thread-safe
  IndexWriter. Draining in completion order — not submission order — is what keeps
  a small file that finishes from waiting behind a slow big file submitted ahead of
  it; each file's chunks are added as soon as that file finishes. The writer is
  committed every ~commit-interval-ms (and once at the end), so files that finish
  while a large file is still embedding become durable promptly and survive an
  interrupt, rather than being lost together with the uncommitted big file. At most
  `index-concurrency` files are in flight, so memory stays bounded. Files whose
  content is unchanged since the last index are skipped without re-embedding (see
  file->chunks), counted under :unchanged — unless opts :force is true, which
  re-embeds every file regardless (the escape hatch for a chunker/schema change the
  content+model gate can't detect; see file->chunks).

  The 3-arg form takes a no-arg `cancelled?` predicate, polled both here (per
  file) and inside the embedder (between batches, via file->chunks). Once it turns
  true the index is *cancelled*: no more files are submitted and in-flight embeds
  are interrupted, but every file that had already finished is folded in and
  committed first (including any waiting in the completion queue), so completed work
  is kept; the result comes back with :cancelled true. rag.http drives it from a
  heartbeat write so a disconnected client stops the indexing — and, because it
  reaches into the embed batches, the Ollama work in flight is bounded to the
  current batch rather than a whole file.
  Returns {:indexed <files> :chunks <n> :skipped :unchanged :errors [{:path ..}]}.

  `on-progress` (optional, default no-op) is called with plain event maps as work
  advances, for a streaming progress UI: once with {:type \"start\" :total <file
  count>} after the walk enumerates, then once per completed file with {:type
  \"file\" :done <files-processed-so-far> :path <abs> :status \"indexed\"|
  \"unchanged\"|\"skipped\"|\"error\" :chunks <n written for this file>}. As each
  file embeds it also emits {:type \"file-start\" :path <abs> :total <chunks>} then
  {:type \"chunk\" :path <abs> :done <so far> :total <chunks>} per batch. The
  `file`/`start` events come from this (the draining) thread in completion order;
  `file-start`/`chunk` events come from the worker threads (up to index-concurrency
  files embed at once), so on-progress must be thread-safe."
  ([paths opts] (index-paths! paths opts (constantly false) (fn [_])))
  ([paths opts cancelled?] (index-paths! paths opts cancelled? (fn [_])))
  ([paths {:keys [recursive force] :or {recursive true}} cancelled? on-progress]
   (let [idx    (ensure!)
         _      (ensure-dim! idx (embed/embedding-dim))  ; refuse a dim change before any embedding work
         spec   (embed/model-spec)  ; one Ollama metadata probe per run; stamped on every chunk
         files  (distinct (mapcat #(walker/walk % {:recursive recursive}) paths))
         _      (on-progress {:type "start" :total (count files)})
         n-files (count files)
         pool   (Executors/newFixedThreadPool config/index-concurrency)
         ecs    (ExecutorCompletionService. pool)  ; hands back futures in completion order
         submit (fn [^java.io.File f]
                  (.submit ecs ^Callable (fn [] (embed-file-result idx spec f cancelled? on-progress force))))
         ;; Fold one file's result into the running counters, writing its chunks to
         ;; the index (as one atomic block). Returns [[indexed chunks skipped
         ;; unchanged errors] status fchunks] — status/fchunks feed the progress UI.
         process (fn [[indexed chunks skipped unchanged errors]
                      {:keys [^java.io.File file error] unchg :unchanged cs :chunks}]
                   (cond
                     error    [[indexed chunks skipped unchanged
                                (conj errors {:path (.getAbsolutePath file) :error error})]
                               "error" 0]
                     unchg    [[indexed chunks skipped (inc unchanged) errors] "unchanged" 0]
                     (seq cs) (do (lucene/add-chunks! idx cs)
                                  [[(inc indexed) (+ chunks (count cs)) skipped unchanged errors]
                                   "indexed" (count cs)])
                     :else    [[indexed chunks (inc skipped) unchanged errors] "skipped" 0]))
         finish (fn [[indexed chunks skipped unchanged errors] cancelled]
                  (lucene/commit! idx)
                  (cond-> {:indexed indexed :chunks chunks :skipped skipped
                           :unchanged unchanged :errors errors}
                    cancelled (assoc :cancelled true)))]
     (try
       (run! submit (take config/index-concurrency files))
       (loop [pending     (drop config/index-concurrency files)
              taken       0
              counters    [0 0 0 0 []]     ; indexed chunks skipped unchanged errors
              dirty?      false            ; chunks added since the last commit
              last-commit (System/currentTimeMillis)]
         (if (= taken n-files)
           (finish counters false)
           (let [result (.get ^Future (.take ecs))]  ; the next file to *finish*, any order
             (if (cancelled?)
               ;; Client gone: stop submitting and interrupt in-flight embeds, but
               ;; first fold in this result and every file already sitting finished
               ;; in the completion queue, then commit — so completed work is kept.
               (let [counters (loop [c (first (process counters result))]
                                (if-let [f (.poll ecs)]
                                  (recur (first (process c (.get ^Future f))))
                                  c))]
                 (.shutdownNow pool)
                 (finish counters true))
               (let [_ (when (seq pending) (submit (first pending)))  ; keep the window full
                     pending (if (seq pending) (rest pending) pending)
                     [counters status fchunks] (process counters result)
                     [indexed chunks skipped unchanged errors] counters
                     ;; Commit periodically so finished files are durable before any
                     ;; interrupt, without an fsync per file.
                     dirty? (or dirty? (= status "indexed"))
                     now    (System/currentTimeMillis)
                     [dirty? last-commit] (if (and dirty? (>= (- now last-commit) commit-interval-ms))
                                            (do (lucene/commit! idx) [false now])
                                            [dirty? last-commit])]
                 (on-progress {:type "file"
                               :done (+ indexed unchanged skipped (count errors))
                               :path (.getAbsolutePath ^java.io.File (:file result))
                               :status status
                               :chunks fchunks})
                 (recur pending (inc taken) counters dirty? last-commit))))))
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
             ;; Embed once, then check the query vector's own length against the
             ;; index dimension — authoritative and Ollama-free (no /api/show on the
             ;; hot path) — before kNN, which would otherwise throw a raw Lucene error.
             "vector"  (let [qvec (embed/embed-query q)]
                         (ensure-dim! comp (alength ^floats qvec))
                         (lucene/vector-search comp qvec k filt))
             "hybrid"  (let [qvec (embed/embed-query q)]
                         (ensure-dim! comp (alength ^floats qvec))
                         (hybrid/fuse
                          (lucene/keyword-search comp q k-each filt)
                          (lucene/vector-search comp qvec k-each filt)
                          k))
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

(defn prune!
  "{:dry-run bool}? -> {:removed [str], :kept int, :dry_run bool?}
  Reconcile the index against disk: find every indexed source file whose path no
  longer exists (orphans left by a delete or rename) and drop all of its chunks,
  then reclaim them so the counts stay honest (lucene/reclaim-deletes!). With
  {:dry-run true} it reports what *would* go without touching the index.
    :removed  the orphaned paths, sorted (as lucene/paths returns them)
    :kept     how many indexed files remain
    :dry_run  present and true only for a dry run
  Detection is disk-existence only: a moved file shows up as an orphan at its old
  path (and, once you reindex the new location, as a fresh file there). Runs on the
  caller's thread; no embedding, just a stat() per indexed file."
  ([] (prune! {}))
  ([{:keys [dry-run]}]
   (let [idx     (ensure!)
         indexed (lucene/paths idx)
         orphans (filterv #(not (.exists (io/file ^String %))) indexed)]
     (when (and (seq orphans) (not dry-run))
       (lucene/delete-by-paths! idx orphans)
       (lucene/reclaim-deletes! idx))
     (cond-> {:removed orphans :kept (- (count indexed) (count orphans))}
       dry-run (assoc :dry_run true)))))

(defn clear!
  "-> {:removed_docs int, :removed_chunks int}
  Wipe the whole index — drop every chunk of every file — then commit and refresh
  so stats/paths immediately read empty. Returns the counts that were removed (the
  index contents read just before the wipe), for the caller to report. Opens the
  index if needed, so a clear on a freshly-booted server still resets on-disk
  state. This is the supported reset after an embedding-model *dimension* change,
  which Lucene can't apply to a live field (lucene/clear!): clear, then re-index."
  []
  (let [idx (ensure!)
        {:keys [num_docs num_chunks]} (lucene/stats idx)]
    (lucene/clear! idx)
    {:removed_docs num_docs :removed_chunks num_chunks}))

(defn stats
  "-> {:num_docs int, :num_chunks int}
  Index counts, or zeros if nothing has opened the index yet."
  []
  (if (open?)
    (lucene/stats @component)
    {:num_docs 0 :num_chunks 0}))

(defn paths
  "-> {:count int, :paths [str]}
  Distinct source-file paths currently indexed, sorted. Opens the index on first
  use (like search, and unlike stats) so it reflects what's actually on disk even
  on a freshly-booted server that hasn't served a query yet — the MCP
  list_indexed_paths tool answers \"do you have access to X?\", so a lazy
  not-yet-opened index must not read back as empty. Backs GET /paths."
  []
  (let [ps (lucene/paths (ensure!))]
    {:count (count ps) :paths ps}))

(defn close!
  "-> nil
  Close and forget the index component. No-op if never opened."
  []
  (when-let [c @component]
    (lucene/close c)
    (reset! component nil)))
