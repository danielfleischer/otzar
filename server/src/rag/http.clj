(ns rag.http
  "Ring/reitit handlers: /health, /stats, /paths, /roots, /index (POST add,
  DELETE clear), /prune, /query, /document/:id, /watch (GET status, POST toggle),
  /shutdown.
  Deletion verbs: /prune drops chunks for source files gone from disk; DELETE /index
  wipes the whole index. /reindex remains unscheduled (docs/api.md)."
  (:require [reitit.ring :as ring]
            [ring.core.protocols :as rcp]
            [jsonista.core :as j]
            [clojure.string :as str]
            [rag.config :as config]
            [rag.index :as index]
            [rag.roots :as roots]
            [rag.watch :as watch])
  (:import [java.io OutputStream IOException]
           [java.util.concurrent Executors TimeUnit]))

(def ^:private start-ms (System/currentTimeMillis))

(def ^:private mapper
  (j/object-mapper {:decode-key-fn keyword}))

(defn- json [status data]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (j/write-value-as-string data)})

(defn- read-body
  "Parse the request's JSON body into a keyword-keyed map, or nil if absent."
  [req]
  (when-let [b (:body req)]
    (j/read-value b mapper)))

(defn- with-errors
  "Turn thrown exceptions into JSON error responses: ex-info (bad request) → 400,
  anything else (including the not-yet-implemented Lucene core) → 500."
  [handler]
  (fn [req]
    (try
      (handler req)
      (catch clojure.lang.ExceptionInfo e
        (json 400 {:error (.getMessage e) :data (ex-data e)}))
      (catch Throwable e
        (json 500 {:error (.getMessage e)})))))

(defn- health
  "GET /health — liveness, plus the two things only the server answering can say
  about itself: its version and where it was booted from.

  Both exist because the client's view of them is a guess. It compares that
  version against its own and warns on a difference (upgrading the binary leaves
  a running server untouched, so the pair drifts until someone restarts it), and
  it shows this source in place of the jar-or-checkout it would boot itself. Old
  clients ignore the extra keys."
  [_]
  (json 200 {:status   "ok"
             :uptime_s (/ (- (System/currentTimeMillis) start-ms) 1000.0)
             :version  config/version
             :source   config/launch-source}))

(defn- stats [_]
  (json 200 (index/stats)))

(defn- paths [_]
  (json 200 (index/paths)))

(defn- roots-handler
  "GET /roots — the trees the user has asked to index (rag.roots), each with the
  recursive flag it was indexed with: {\"roots\": [{\"path\", \"recursive\"}]}. Not the
  same list as /paths, which is the files that actually made it into the index.

  This is what lets the client re-run an index over what is already known —
  `otzar index --now` fetches these and posts them back to /index, the same way
  `otzar admin reindex` composes /paths with /index. The background watch sweep
  reads the same file server-side."
  [_]
  (json 200 {:roots (roots/read-roots)}))

(def ^:private heartbeat-secs
  "How often, in seconds, to poke the response socket while indexing so we notice
  a disconnected client within roughly this bound (independent of file size)."
  2)

(defn- index-handler
  "Index the requested paths, streaming the response as newline-delimited JSON so
  the client can render live progress and a disconnected client cancels the work.
  index-paths! calls back with `start` and `file` events, each written as one JSON
  line; the terminal line is {\"type\":\"result\" ...} (or {\"type\":\"error\"}).

  A background timer also writes a heartbeat space every `heartbeat-secs` and
  flushes; when the client is gone the flush (from either the timer or an event
  write) throws and we flip a `gone` flag that index-paths! polls — per file, and
  inside the embedder between batches — so indexing (and the Ollama work in flight)
  stops within ~one heartbeat + one embed batch. The heartbeat keeps that bound
  even during a single long file, when no `file` event is in flight to probe with.
  A lone heartbeat space is whitespace on the front of the next JSON line, so a
  line-oriented client just trims it."
  [req]
  (let [{:keys [paths recursive force] :or {recursive true}} (read-body req)]
    (if (empty? paths)
      (json 400 {:error "index requires a non-empty \"paths\" array"})
      (do
       ;; Remember what was asked to index so the watch sweep (rag.watch) can keep
       ;; re-scanning it for new/changed/deleted files. Recorded up front so even a
       ;; client that disconnects mid-index still leaves the root registered.
       (roots/add-roots! paths recursive)
       {:status  200
       :headers {"Content-Type" "application/x-ndjson"}
       :body
       (reify rcp/StreamableResponseBody
         (write-body-to-stream [_ _ out]
           (let [^OutputStream out out
                 gone  (atom false)     ; set by a failed write = client left
                 lock  (Object.)        ; serialize the writer threads onto `out`
                 emit  (fn [event]      ; one NDJSON line; a write failure = gone
                         (locking lock
                           (try
                             (.write out (.getBytes (str (j/write-value-as-string event) "\n") "UTF-8"))
                             (.flush out)
                             (catch IOException _ (reset! gone true)))))
                 beat  (fn []
                         (locking lock
                           (try (.write out (int \space)) (.flush out)
                                (catch IOException _ (reset! gone true)))))
                 sched (Executors/newSingleThreadScheduledExecutor)]
             (.scheduleAtFixedRate sched ^Runnable beat
                                   heartbeat-secs heartbeat-secs TimeUnit/SECONDS)
             (let [result (try (index/index-paths! paths {:recursive recursive :force force}
                                                   (fn [] @gone) emit)
                               (catch Throwable t {::error (.getMessage t)}))]
               (.shutdownNow sched)     ; no more heartbeats before the final line
               (emit (if-let [e (::error result)]
                       {:type "error" :error e}
                       {:type "result" :result result}))
               (.close out)))))}))))

(defn- prune-handler
  "POST /prune — reconcile the index against disk, dropping chunks for source files
  that no longer exist (orphans from deletes/renames). Optional body
  {\"dry_run\": true} previews the orphans without deleting. Returns
  {:removed [path] :kept n [:dry_run true]} (see index/prune!)."
  [req]
  (let [{:keys [dry_run]} (read-body req)]
    (json 200 (index/prune! {:dry-run dry_run}))))

(defn- clear-handler
  "DELETE /index — wipe the whole index (every chunk of every file), commit, and
  refresh. Irreversible; the client gates it behind a confirmation. Returns the
  counts removed {:removed_docs n :removed_chunks n} (see index/clear!). This is
  the supported reset after an embedding-dimension change."
  [_]
  (json 200 (index/clear!)))

(defn- watch-handler
  "GET /watch — current state of the background auto-reindex sweep (rag.watch):
  {:enabled bool :interval_s n :indexing bool [:last_sweep_at :last_result]}."
  [_]
  (json 200 (watch/status)))

(defn- watch-set-handler
  "POST /watch — toggle the background sweep. Body {\"enabled\": bool,
  \"interval_s\": n?}: enabled true arms it (optionally at interval_s seconds, else
  the current/default interval), enabled false disables it. The setting persists to
  watch.edn so it survives a restart. Returns the resulting status (see
  watch-handler)."
  [req]
  (let [{:keys [enabled interval_s]} (read-body req)]
    (json 200 (if enabled
                (watch/enable! interval_s)
                (watch/disable!)))))

(defn- query-handler [req]
  (let [{:keys [q k mode filters fusion] :or {k 10 mode "hybrid"}} (read-body req)]
    (if (str/blank? q)
      (json 400 {:error "query requires a non-blank \"q\""})
      (json 200 (index/search {:q q :k k :mode mode :filters filters :fusion fusion})))))

(defn- document-handler
  "GET /document/:id — fetch one chunk or a whole file by id (see index/document).
  404 when the id isn't indexed, or the file backing a doc_id is gone from disk."
  [req]
  (let [id (get-in req [:path-params :id])]
    (if (str/blank? id)
      (json 400 {:error "document requires an id"})
      (if-let [doc (index/document id)]
        (json 200 doc)
        (json 404 {:error (str "no indexed document or chunk for id \"" id "\"")})))))

(defn- shutdown [_]
  ;; Respond first, then exit so the client sees {ok true}. The JVM shutdown hook
  ;; (see rag.main) closes the index and removes the port file on the way out.
  (future (Thread/sleep 150) (System/exit 0))
  (json 200 {:ok true}))

(defn app
  "-> Ring handler
  Build the reitit Ring handler for the M1 route surface, with a default handler
  for unmatched routes."
  []
  (ring/ring-handler
   (ring/router
    [["/health"   {:get  health}]
     ["/stats"    {:get  (with-errors stats)}]
     ["/paths"    {:get  (with-errors paths)}]
     ["/roots"    {:get  (with-errors roots-handler)}]
     ["/index"    {:post   (with-errors index-handler)
                   :delete (with-errors clear-handler)}]
     ["/prune"    {:post (with-errors prune-handler)}]
     ["/watch"    {:get  (with-errors watch-handler)
                   :post (with-errors watch-set-handler)}]
     ["/query"    {:post (with-errors query-handler)}]
     ["/document/:id" {:get (with-errors document-handler)}]
     ["/shutdown" {:post shutdown}]])
   (ring/create-default-handler)))
