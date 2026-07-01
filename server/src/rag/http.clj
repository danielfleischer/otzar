(ns rag.http
  "Ring/reitit handlers. M1 surface: /health, /stats, /index, /query, /shutdown.
  Later milestones add /reindex, DELETE /index, /document/:id."
  (:require [reitit.ring :as ring]
            [jsonista.core :as j]
            [clojure.string :as str]
            [rag.index :as index]))

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

(defn- index-handler [req]
  (let [{:keys [paths recursive] :or {recursive true}} (read-body req)]
    (if (empty? paths)
      (json 400 {:error "index requires a non-empty \"paths\" array"})
      (json 200 (index/index-paths! paths {:recursive recursive})))))

(defn- query-handler [req]
  (let [{:keys [q k mode] :or {k 10 mode "keyword"}} (read-body req)]
    (if (str/blank? q)
      (json 400 {:error "query requires a non-blank \"q\""})
      (json 200 (index/search {:q q :k k :mode mode})))))

(defn- shutdown [_]
  ;; Respond first, then exit so the client sees {ok true}. The JVM shutdown hook
  ;; (see rag.main) closes the index and removes the port file on the way out.
  (future (Thread/sleep 150) (System/exit 0))
  (json 200 {:ok true}))

(defn app []
  (ring/ring-handler
   (ring/router
    [["/health"   {:get  health}]
     ["/stats"    {:get  (with-errors stats)}]
     ["/index"    {:post (with-errors index-handler)}]
     ["/query"    {:post (with-errors query-handler)}]
     ["/shutdown" {:post shutdown}]])
   (ring/create-default-handler)))
