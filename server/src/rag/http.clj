(ns rag.http
  "Ring/reitit handlers. M0 surface only: /health, /stats, /shutdown.
  Later milestones add /index, /reindex, /query, /document/:id."
  (:require [reitit.ring :as ring]
            [jsonista.core :as j]))

(def ^:private start-ms (System/currentTimeMillis))

(defn- json [status data]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (j/write-value-as-string data)})

(defn- health [_]
  (json 200 {:status   "ok"
             :uptime_s (/ (- (System/currentTimeMillis) start-ms) 1000.0)}))

(defn- stats [_]
  ;; Placeholder until the Lucene index lands (M1+).
  (json 200 {:num_docs 0 :num_chunks 0}))

(defn- shutdown [_]
  ;; Respond first, then exit so the client sees {ok true}. The JVM shutdown
  ;; hook (see rag.main) removes the port file on the way out.
  (future (Thread/sleep 150) (System/exit 0))
  (json 200 {:ok true}))

(defn app []
  (ring/ring-handler
   (ring/router
    [["/health"   {:get  health}]
     ["/stats"    {:get  stats}]
     ["/shutdown" {:post shutdown}]])
   (ring/create-default-handler)))
