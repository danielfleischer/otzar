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
            [rag.roots :as roots]
            [rag.query.hybrid :as hybrid])
  (:import [java.security MessageDigest]
           [java.time Instant LocalDate ZoneOffset]
           [java.util.concurrent ExecutorCompletionService Executors Future]
           [java.util.concurrent.locks ReentrantLock]))

(defonce ^:private component (atom nil))

;; Single-flight guard over *every* mutation of the index — index-paths!, reindex!,
;; the background watch sweep, prune! and clear!. One at a time, so none of them can
;; observe or overwrite another's half-finished work: a clear landing mid-index used
;; to be undone by that run's own commits (it reported "0 removed" and left the
;; files indexed), and a prune's forceMergeDeletes ran against a writer another
;; thread was still adding to.
;;
;; The two ways to take it differ by what a caller can usefully do while it waits.
;; The long-running writers — index-paths! and reindex! — block: they always run,
;; just after whatever is in progress, and their HTTP stream is bounded by silence
;; rather than by a deadline. The short ones — prune!, clear! and the sweep tick —
;; don't: their HTTP calls carry the client's 10s budget, so blocking behind an
;; hours-long index would time the client out and *then* mutate the index behind its
;; back. They take it with tryLock instead; the sweep skips the tick, while prune!
;; and clear! throw :index-busy for the caller to retry (rag.http maps it to 409).
;;
;; Reentrant, so sweep! can hold it across its own calls into prune! and index-paths!.
(defonce ^:private mutation-lock (ReentrantLock.))

(defn indexing?
  "-> bool
  True while an index mutation (a manual index, a sweep, a prune, a clear) holds
  the single-flight lock."
  []
  (.isLocked mutation-lock))

(defn- with-mutation-lock*
  "Run `f` holding the mutation lock, taking it with tryLock and throwing an ex-info
  {:type :index-busy} rather than waiting when another mutation has it. For the
  short verbs whose caller is better served by an immediate \"try again\" than by a
  connection held open for the length of an index run — see the lock's comment."
  [what f]
  (if-not (.tryLock mutation-lock)
    (throw (ex-info (str what " can't run right now: an index run is in progress. "
                         "Wait for it to finish (`otzar server watch` shows whether one "
                         "is going) and try again.")
                    {:type :index-busy :operation what}))
    (try (f) (finally (.unlock mutation-lock)))))

(def ^:private commit-interval-ms
  "Commit the index at least this often during a long run so files that finish
  while a big file is still embedding become durable promptly and survive an
  interrupt. A commit fsyncs, so this trades a little throughput for a small
  worst-case loss window; per-file atomicity is unaffected."
  5000)

(defn- open? [] (some? @component))

(defn ensure!
  "-> component
  Return the open Lucene component, opening it under ~/.otzar/index on first use.
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
       "  otzar admin clear\n"
       "  otzar index <paths…>"))

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
    nil          nothing to index and nothing indexed before — a plain skip
    ::stale      nothing to index, but this file *is* in the index: its old chunks
                 are no longer backed by anything on disk and must be dropped
    ::unchanged  already indexed with identical content — skip the costly embed.
  \"Nothing to index\" is the extractor declining the file (unsupported type, empty,
  binary, over the size ceiling, unreadable) or an extraction that yields no chunks
  at all (a file emptied down to whitespace). Either is fine for a file we have
  never seen — but for one already indexed it means its stored chunks are stale, and
  leaving them behind would keep the old text searchable under a path whose content
  is gone. That is the ::stale case; index-paths! deletes the file's chunks.
  Change detection is model-spec → mtime → content-hash. `spec` is the identity of
  the embedding model we'd use now (embed/model-spec); a prior chunk is *reusable*
  only if it was embedded with that same spec — switching OTZAR_EMBED_MODEL (or a
  re-pull that bumps the digest) makes the stored vectors stale, so it forces a
  re-embed even when the bytes are untouched. Given a reusable prior: if the file's
  mtime still matches, it's skipped *without even reading it*; otherwise we read +
  hash and still skip if the content sha1 matches (an mtime bump with no real edit).
  A file with no reusable prior may still be content we already embedded under
  another path — moved or renamed. Before embedding, its chunks are looked up by
  content hash + spec (lucene/vectors-by-content); if every chunk's text is found,
  those vectors are carried over and the embedder is skipped.
  Only genuinely new/changed/re-modelled files reach the embedder, whose vectors are
  attached as :embedding; every chunk also carries :content_sha1 and :model_spec for
  next time. (A change in embedding *dimension* additionally requires a full reset
  — `otzar admin clear`, then re-index — since Lucene stores one vector dimension
  per field.)
  `force?` bypasses all of that, the content lookup included: every file is re-extracted, re-chunked and
  re-embedded even when nothing changed. It's the escape hatch for changes the
  content/model gate can't see — a new chunker or a chunk-schema field (e.g. adding
  :loc_start/:loc_end): the bytes and model are identical, so only an explicit
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
        reusable? (and (not force?) prior (= spec (:model_spec prior)))
        ;; What "nothing to index" means here depends on whether we have chunks on
        ;; file for this path: stale ones to drop, or simply nothing to do. A file
        ;; that is *gone* is deliberately not our business but prune!'s, which guards
        ;; against the unmounted-volume case where a whole tree reads as missing at
        ;; once. The walker already filters those out, so the .exists check only
        ;; catches a file deleted between the walk and here — but it is what keeps
        ;; that boundary true even then.
        nothing   (when (and prior (.exists f)) ::stale)]
    (if (and reusable? (= mtime (:mtime prior)))
      ::unchanged
      (if-let [text (extractor/extract f)]
        (let [csha (sha1 text)]
          (if (and reusable? (= csha (:content_sha1 prior)))
            ::unchanged
            (let [ext    (extractor/ext f)
                  loc    (extractor/locator f)
                  chunks (chunker/pack (segmenter/segment text loc))]
              (if (empty? chunks)
                nothing
                (let [moved    (when-not force? (lucene/vectors-by-content component csha spec))
                      vecs     (if (every? #(contains? moved (:text %)) chunks)
                                 (mapv #(get moved (:text %)) chunks)
                                 (let [_        (on-progress {:type "file-start" :path path :total (count chunks)})
                                       on-batch (fn [done total]
                                                  (on-progress {:type "chunk" :path path :done done :total total}))]
                                   (embed/embed-documents (map :text chunks) cancelled? on-batch)))]
                  (mapv (fn [{:keys [chunk_idx text loc_start loc_end]} embedding]
                          {:id           (str doc-id ":" chunk_idx)
                           :doc_id       doc-id
                           :path         path
                           :ext          ext
                           :mtime        mtime
                           :chunk_idx    chunk_idx
                           :locator      loc
                           :loc_start    loc_start
                           :loc_end      loc_end
                           :text         text
                           :content_sha1 csha
                           :model_spec   spec
                           :embedding    embedding})
                        chunks vecs))))))
        nothing))))

(defn- embed-file-result
  "Run the change-check + extract+chunk+embed for one file (the Ollama-bound part
  we want off the writer thread). Never throws — the result is a map tagged with
  the source file so the writer thread can attribute it:
    {:file f :unchanged true}   already indexed, identical content (nothing embedded)
    {:file f :stale true}       nothing to index, but its old chunks are still there
    {:file f :chunks <seq>}     ok; nil chunks means this file was never indexable
    {:file f :error <msg>}      extract or embed threw (incl. a cancelled embed)"
  [component spec ^java.io.File f cancelled? on-progress force?]
  (try
    (let [r (file->chunks component spec f cancelled? on-progress force?)]
      (case r
        ::unchanged {:file f :unchanged true}
        ::stale     {:file f :stale true}
        {:file f :chunks r}))
    (catch Exception e {:file f :error (.getMessage e)})))

(defn index-paths!
  "paths:[str|File], {:recursive bool}, cancelled?:(->bool)?, on-progress:(fn[event])? -> {:indexed :chunks :skipped :unchanged :removed :errors [..] :cancelled?}
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

  A file that *was* indexed but no longer yields anything — emptied, overwritten
  with binary, grown past the size ceiling, or turned unreadable, but still there on
  disk (a file that is simply *gone* is prune!'s business) — has its stored
  chunks deleted rather than left behind (file->chunks tags it ::stale), and is
  counted under :removed. Without that, the old text stays searchable under a path
  whose content is gone. Deleted docs are expunged once at the end of the run (the
  same reclaim prune! does) so :num_docs and /paths read honestly straight away;
  that only happens when something was actually removed.

  The 3-arg form takes a no-arg `cancelled?` predicate, polled both here (per
  file) and inside the embedder (between batches, via file->chunks). Once it turns
  true the index is *cancelled*: no more files are submitted and in-flight embeds
  are interrupted, but every file that had already finished is folded in and
  committed first (including any waiting in the completion queue), so completed work
  is kept; the result comes back with :cancelled true. rag.http drives it from a
  heartbeat write so a disconnected client stops the indexing — and, because it
  reaches into the embed batches, the Ollama work in flight is bounded to the
  current batch rather than a whole file.
  Returns {:indexed <files> :chunks <n> :skipped :unchanged :removed
  :errors [{:path ..}]}.

  `on-progress` (optional, default no-op) is called with plain event maps as work
  advances, for a streaming progress UI: once with {:type \"start\" :total <file
  count>} after the walk enumerates, then once per completed file with {:type
  \"file\" :done <files-processed-so-far> :path <abs> :status \"indexed\"|
  \"unchanged\"|\"skipped\"|\"removed\"|\"error\" :chunks <n written for this file>}. As each
  file embeds it also emits {:type \"file-start\" :path <abs> :total <chunks>} then
  {:type \"chunk\" :path <abs> :done <so far> :total <chunks>} per batch. The
  `file`/`start` events come from this (the draining) thread in completion order;
  `file-start`/`chunk` events come from the worker threads (up to index-concurrency
  files embed at once), so on-progress must be thread-safe."
  ([paths opts] (index-paths! paths opts (constantly false) (fn [_])))
  ([paths opts cancelled?] (index-paths! paths opts cancelled? (fn [_])))
  ([paths {:keys [recursive force] :or {recursive true}} cancelled? on-progress]
   (.lock mutation-lock)  ; single-flight: wait out any in-progress mutation, then take over
   (try
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
         ;; the index (as one atomic block) or dropping the stale ones it left
         ;; behind. Returns [[indexed chunks skipped unchanged removed errors]
         ;; status fchunks] — status/fchunks feed the progress UI.
         process (fn [[indexed chunks skipped unchanged removed errors]
                      {:keys [^java.io.File file error stale] unchg :unchanged cs :chunks}]
                   (cond
                     error    [[indexed chunks skipped unchanged removed
                                (conj errors {:path (.getAbsolutePath file) :error error})]
                               "error" 0]
                     unchg    [[indexed chunks skipped (inc unchanged) removed errors] "unchanged" 0]
                     ;; Indexed before, unextractable now: its chunks no longer
                     ;; describe anything on disk, so they go.
                     stale    (do (lucene/delete-by-paths! idx [(.getAbsolutePath file)])
                                  [[indexed chunks skipped unchanged (inc removed) errors]
                                   "removed" 0])
                     (seq cs) (do (lucene/add-chunks! idx cs)
                                  [[(inc indexed) (+ chunks (count cs)) skipped unchanged removed errors]
                                   "indexed" (count cs)])
                     :else    [[indexed chunks (inc skipped) unchanged removed errors] "skipped" 0]))
         finish (fn [[indexed chunks skipped unchanged removed errors] cancelled]
                  ;; A delete only marks docs; the term dictionary — and so /paths
                  ;; and :num_docs — keeps them until the segment is rewritten, so
                  ;; a run that dropped anything reclaims (which commits) instead.
                  (if (pos? removed)
                    (lucene/reclaim-deletes! idx)
                    (lucene/commit! idx))
                  (cond-> {:indexed indexed :chunks chunks :skipped skipped
                           :unchanged unchanged :removed removed :errors errors}
                    cancelled (assoc :cancelled true)))]
     (try
       (run! submit (take config/index-concurrency files))
       (loop [pending     (drop config/index-concurrency files)
              taken       0
              counters    [0 0 0 0 0 []]   ; indexed chunks skipped unchanged removed errors
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
                     [indexed chunks skipped unchanged removed errors] counters
                     ;; Commit periodically so finished files are durable before any
                     ;; interrupt, without an fsync per file. A removal counts as
                     ;; dirty too — a deletion left uncommitted is stale text still
                     ;; searchable after an interrupt.
                     dirty? (or dirty? (contains? #{"indexed" "removed"} status))
                     now    (System/currentTimeMillis)
                     [dirty? last-commit] (if (and dirty? (>= (- now last-commit) commit-interval-ms))
                                            (do (lucene/commit! idx) [false now])
                                            [dirty? last-commit])]
                 (on-progress {:type "file"
                               :done (+ indexed unchanged skipped removed (count errors))
                               :path (.getAbsolutePath ^java.io.File (:file result))
                               :status status
                               :chunks fchunks})
                 (recur pending (inc taken) counters dirty? last-commit))))))
       (finally (.shutdown pool))))
   (finally (.unlock mutation-lock)))))

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
  path (and, once you reindex the new location, as a fresh file there — which
  reuses the old path's vectors only if this hasn't dropped them yet, so callers
  reindex first and prune after). Runs on the
  caller's thread; no embedding, just a stat() per indexed file.

  A real prune takes the mutation lock and throws :index-busy if an index run holds
  it — its reclaim rewrites segments underneath the writer, so it cannot share the
  index with a run in flight. A dry run touches nothing, so it stays a free read and
  answers even mid-index."
  ([] (prune! {}))
  ([{:keys [dry-run]}]
   (let [run (fn []
               (let [idx     (ensure!)
                     indexed (lucene/paths idx)
                     orphans (filterv #(not (.exists (io/file ^String %))) indexed)]
                 (when (and (seq orphans) (not dry-run))
                   (lucene/delete-by-paths! idx orphans)
                   (lucene/reclaim-deletes! idx))
                 (cond-> {:removed orphans :kept (- (count indexed) (count orphans))}
                   dry-run (assoc :dry_run true))))]
     (if dry-run
       (run)
       (with-mutation-lock* "prune" run)))))

(defn- under?
  "prefix:str, p:str -> bool
  True when indexed path `p` is `prefix` itself or a file somewhere beneath it.
  The separator is appended deliberately: a bare prefix test would make
  \"/a/notes\" swallow \"/a/notes-old/x.md\"."
  [^String prefix ^String p]
  (or (= p prefix)
      (str/starts-with? p (str prefix "/"))))

(defn- still-wanted?
  "roots:[{:path :recursive}], p:str -> bool
  True when one of `roots` is a standing instruction to keep `p` indexed — i.e.
  the next sweep would walk to it. A recursive root claims its whole subtree; a
  non-recursive one claims only itself and the files directly inside it (what
  walker/walk yields for it). Used to spare files that another root still covers
  from a forget!, since dropping them would only cost the next sweep a re-embed."
  [roots ^String p]
  (boolean
   (some (fn [{:keys [^String path recursive]}]
           (if recursive
             (under? path p)
             (or (= p path)
                 (= (.getParent (io/file p)) path))))
         roots)))

(defn forget!
  "paths:[str], {:dry-run bool}? -> {:removed_files [str], :removed_chunks int,
                                    :kept_covered int, :dry_run bool?}
  Drop from the index every file at or beneath `paths` — the content half of
  \"stop indexing this\" (rag.roots/remove-roots! is the intent half; DELETE /roots
  does both). Unlike prune!, which only ever drops what disk says is gone, this
  deletes files that are present and readable, because the user asked for it by
  name.

  `paths` need not be recorded roots. Un-indexing a tree and cleaning up after one
  you un-indexed earlier are the same sentence, and only the second can reach files
  that no root covers any more.

  Files a *remaining* root still claims are kept (see still-wanted?): removing a
  nested root shouldn't delete chunks the enclosing root would re-embed on the next
  sweep. The caller decides which roots remain — read them before forgetting, so
  the paths being dropped are not among them.

    :removed_files  the indexed paths dropped, sorted (as lucene/paths returns them)
    :removed_chunks how many chunks those files held
    :kept_covered   how many matching files were spared because a remaining root
                    still covers them — without it a \"dropped 0 files\" over a tree
                    that plainly is indexed reads as a bug rather than an answer
    :dry_run        present and true only for a dry run

  A real forget takes the mutation lock and throws :index-busy if an index run
  holds it — like prune!, its reclaim rewrites segments underneath the writer. A
  dry run touches nothing, so it answers even mid-index."
  ([paths] (forget! paths {}))
  ([paths {:keys [dry-run]}]
   (let [run (fn []
               (let [idx     (ensure!)
                     keep    (remove (comp (set (map str paths)) :path) (roots/read-roots))
                     matched (filterv (fn [p] (some #(under? (str %) p) paths))
                                      (lucene/paths idx))
                     targets (filterv #(not (still-wanted? keep %)) matched)
                     chunks  (transduce (map #(:num_chunks (lucene/doc-summary idx (sha1 %)) 0))
                                        + 0 targets)]
                 (when (and (seq targets) (not dry-run))
                   (lucene/delete-by-paths! idx targets)
                   (lucene/reclaim-deletes! idx))
                 (cond-> {:removed_files  targets
                          :removed_chunks chunks
                          :kept_covered   (- (count matched) (count targets))}
                   dry-run (assoc :dry_run true))))]
     (if dry-run
       (run)
       (with-mutation-lock* "forget" run)))))

(defn sweep!
  "-> summary map
  One background watch sweep (driven by rag.watch on an interval): reconcile the
  persisted roots (rag.roots) against disk and re-index anything new or changed.
  Single-flighted against every other index mutation via the same lock — if one is
  already in progress it does nothing and returns {:skipped true}, rather than
  making a background tick wait on (or fight with) work the user asked for.

  Otherwise, when at least one root is recorded:
    - Re-index every present root. The change gate in file->chunks makes an
      unchanged tree cheap — a stat + doc-meta lookup per file, no embedding, so the
      Ollama model is never loaded — and only real changes reach the embedder.
    - Then prune orphans, but ONLY when every root still exists on disk. Then, not
      first: a moved file's old path is an orphan, and its chunks are what lets the
      new path reuse their vectors instead of re-embedding (see file->chunks). The
      all-present condition is the safety valve against mass deletion: if a root
      has vanished (an unmounted volume, a moved directory) every file under it
      would look like an orphan, so rather than delete a whole subtree we skip
      pruning this tick and leave it to a manual `otzar admin prune`. A genuine
      single-file delete is picked up on the next sweep once the picture is
      unambiguous.
  With no roots recorded yet it is a no-op (never prunes), so a pre-existing index
  is untouched until the user indexes something and populates roots.edn.

  Summary keys (also the JSON shape surfaced by GET /watch's :last_result):
    :skipped        true when a run was already in progress (nothing else present)
    :roots :present counts of recorded vs. currently-on-disk roots
    :pruned         orphan paths dropped (absent/empty when pruning was skipped)
    :indexed :chunks :unchanged :skipped_files :removed_files  folded index-paths!
                    counters (:removed_files being files whose content stopped being
                    extractable, so their stale chunks were dropped)
    :errors         per-file errors from the re-index"
  []
  (if-not (.tryLock mutation-lock)
    {:skipped true}
    (try
      (let [all-roots (roots/read-roots)]
        (if (empty? all-roots)
          {:roots 0 :present 0 :indexed 0 :chunks 0 :unchanged 0 :skipped_files 0
           :removed_files 0 :errors []}
          (let [present  (filterv #(.exists (io/file ^String (:path %))) all-roots)
                all?     (= (count present) (count all-roots))
                by-rec   (group-by :recursive present)
                run      (fn [rs recursive]
                           (when (seq rs)
                             (index-paths! (mapv :path rs) {:recursive recursive})))
                results  (remove nil? [(run (get by-rec true) true)
                                       (run (get by-rec false) false)])
                pruned   (when all? (:removed (prune! {})))
                sum     (fn [k] (reduce + (map #(get % k 0) results)))]
            {:roots         (count all-roots)
             :present       (count present)
             :pruned        (vec pruned)
             :indexed       (sum :indexed)
             :chunks        (sum :chunks)
             :unchanged     (sum :unchanged)
             :skipped_files (sum :skipped)
             :removed_files (sum :removed)
             :errors        (into [] (mapcat :errors) results)})))
      (finally (.unlock mutation-lock)))))

(defn clear!
  "-> {:removed_docs int, :removed_chunks int}
  Wipe the whole index — drop every chunk of every file — then commit and refresh
  so stats/paths immediately read empty. Returns the counts that were removed (the
  index contents read just before the wipe), for the caller to report. Opens the
  index if needed, so a clear on a freshly-booted server still resets on-disk
  state. This is the supported reset after an embedding-model *dimension* change,
  which Lucene can't apply to a live field (lucene/clear!): clear, then re-index.

  Takes the mutation lock and throws :index-busy if an index run holds it. A wipe
  that raced a run was worse than useless: the run's own commits put the files
  straight back, so the clear reported the counts it had removed and left an index
  that still held them."
  []
  (with-mutation-lock* "clear"
    (fn []
      (let [idx (ensure!)
            {:keys [num_docs num_chunks]} (lucene/stats idx)]
        (lucene/clear! idx)
        {:removed_docs num_docs :removed_chunks num_chunks}))))

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

(defn reindex!
  "cancelled?:(->bool)?, on-progress:(fn[event])? -> summary map
  Rebuild the index from its own contents: re-extract, re-chunk and re-embed every
  file currently indexed, whether or not anything changed. The escape hatch for a
  change the incremental gate can't detect — a new chunker, a new chunk field, a
  different embedding model of the same dimension (a dimension change needs
  `clear!` first; index-paths! refuses it up front either way).

  The file list is the index's own (`paths`), not the persisted roots: the point is
  to rebuild exactly what is there, so nothing about what the user asked to keep
  indexed is read or written. They are concrete files, so `recursive` is
  irrelevant; one deleted since indexing simply yields nothing and lands in
  :skipped (prune! is what drops it). Returns index-paths!'s summary, or its zeroes
  when nothing is indexed. Backs POST /reindex."
  ([] (reindex! (constantly false) (fn [_])))
  ([cancelled? on-progress]
   ;; Blocking, and held across the `paths` read as well as the rebuild: the file
   ;; list is a snapshot of the index, so taking it before the lock would let a
   ;; concurrent clear or prune make it stale before a single file is re-embedded.
   ;; Reentrant, so the nested index-paths! takes it again for free.
   (.lock mutation-lock)
   (try
     (let [ps (:paths (paths))]
       (if (empty? ps)
         {:indexed 0 :chunks 0 :skipped 0 :unchanged 0 :removed 0 :errors []}
         (index-paths! ps {:recursive false :force true} cancelled? on-progress)))
     (finally (.unlock mutation-lock)))))

(defn close!
  "-> nil
  Close and forget the index component. No-op if never opened."
  []
  (when-let [c @component]
    (lucene/close c)
    (reset! component nil)))
