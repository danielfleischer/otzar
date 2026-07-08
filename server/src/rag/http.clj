(ns rag.http
  "Ring/reitit handlers: /health, /stats, /index, /query, /document/:id, /shutdown.
  Deletion verbs (DELETE /index, /reindex) are unscheduled — see docs/api.md."
  (:require [reitit.ring :as ring]
            [ring.core.protocols :as rcp]
            [jsonista.core :as j]
            [clojure.string :as str]
            [rag.index :as index])
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

(defn- health [_]
  (json 200 {:status   "ok"
             :uptime_s (/ (- (System/currentTimeMillis) start-ms) 1000.0)}))

(defn- stats [_]
  (json 200 (index/stats)))

(def ^:private heartbeat-secs
  "How often, in seconds, to poke the response socket while indexing so we notice
  a disconnected client within roughly this bound (independent of file size)."
  2)

(defn- index-handler
  "Index the requested paths, streaming the response so a disconnected client
  cancels the work. A background timer writes a heartbeat space every
  `heartbeat-secs` and flushes; when the client is gone the flush throws and we
  flip a `gone` flag that index-paths! polls — per file, and inside the embedder
  between batches — so indexing (and the Ollama work in flight) stops within
  ~one heartbeat + one embed batch. The heartbeats are JSON-insignificant
  whitespace before the final result object, so clients need no special handling."
  [req]
  (let [{:keys [paths recursive] :or {recursive true}} (read-body req)]
    (if (empty? paths)
      (json 400 {:error "index requires a non-empty \"paths\" array"})
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body
       (reify rcp/StreamableResponseBody
         (write-body-to-stream [_ _ out]
           (let [^OutputStream out out
                 gone  (atom false)     ; set by a failed heartbeat = client left
                 lock  (Object.)        ; serialize the two writer threads onto `out`
                 beat  (fn []
                         (locking lock
                           (try (.write out (int \space)) (.flush out)
                                (catch IOException _ (reset! gone true)))))
                 sched (Executors/newSingleThreadScheduledExecutor)]
             (.scheduleAtFixedRate sched ^Runnable beat
                                   heartbeat-secs heartbeat-secs TimeUnit/SECONDS)
             (let [result (try (index/index-paths! paths {:recursive recursive}
                                                   (fn [] @gone))
                               (catch Throwable t {:error (.getMessage t)}))]
               (.shutdownNow sched)     ; no more heartbeats before the final write
               (locking lock
                 (try
                   (.write out (.getBytes (j/write-value-as-string result) "UTF-8"))
                   (.flush out)
                   (catch IOException _ nil)))   ; client already gone; nothing to send
               (.close out)))))})))

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
     ["/index"    {:post (with-errors index-handler)}]
     ["/query"    {:post (with-errors query-handler)}]
     ["/document/:id" {:get (with-errors document-handler)}]
     ["/shutdown" {:post shutdown}]])
   (ring/create-default-handler)))
