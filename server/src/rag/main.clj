(ns rag.main
  "Entry point. Binds Jetty to an ephemeral port (0), discovers the actual port
  from the connector, and publishes it to ~/.myrag/server.port so the Go client
  can find us. Optionally (dev only — see start-nrepl!) starts a loopback nREPL so
  CIDER can attach to the *running* server and redefine code live. The client owns
  process lifecycle; /shutdown handles exit."
  (:require [rag.http :as http]
            [rag.config :as config]
            [rag.index :as index]
            [ring.adapter.jetty :as jetty])
  (:import [java.io File])
  (:gen-class))

(defn- write-port! [port]
  (config/ensure-dir!)
  (spit config/port-file (str port)))

(def ^:private nrepl-port-file
  "CIDER auto-discovers .nrepl-port in the project root; the server's working dir
  is the server/ dir (see client lifecycle.go), so we drop it there."
  (File. ".nrepl-port"))

(defn- start-nrepl!
  "-> nREPL server | nil
  When enabled (config/nrepl-enabled?), start the loopback nREPL with the CIDER
  handler and publish .nrepl-port so CIDER can attach to the running server.
  nREPL and CIDER are resolved dynamically (requiring-resolve), so the classes
  only need to exist under the :dev alias — a production `clojure -M:run` neither
  loads them nor pays their memory. Returns the server (to stop on shutdown), or
  nil if disabled or the deps aren't on the classpath (logged, non-fatal)."
  []
  (when config/nrepl-enabled?
    (try
      (let [start   (requiring-resolve 'nrepl.server/start-server)
            handler @(requiring-resolve 'cider.nrepl/cider-nrepl-handler)
            srv     (start :port config/nrepl-port :handler handler)]
        (spit nrepl-port-file (str (:port srv)))
        (println "nREPL listening on port" (:port srv))
        srv)
      (catch Exception e
        (println "nREPL not started (dev deps absent?):" (.getMessage e))
        nil))))

(defn- stop-nrepl!
  "Stop the nREPL `srv` (if any) and remove .nrepl-port. stop-server is resolved
  dynamically for the same reason as start-nrepl!."
  [srv]
  (when srv
    ((requiring-resolve 'nrepl.server/stop-server) srv)
    (.delete nrepl-port-file)))

(defn -main
  "& _args -> never returns
  Start Jetty (on an ephemeral port) and, in dev, the nREPL; publish the ports so
  the client and CIDER can discover them, register a shutdown hook that closes the
  index and removes the port files, then block on the server."
  [& _args]
  (config/ensure-dir!)
  ;; Open the Lucene index eagerly (it's otherwise opened lazily on first query).
  ;; This acquires the single-writer index lock at boot, so a duplicate server —
  ;; one started outside the client's startup lock — fails fast here instead of
  ;; coming up "healthy" and only erroring on the first query that touches the
  ;; index. The client (lifecycle.Start) then reports it as a startup crash.
  (index/ensure!)
  (let [nrepl-srv (start-nrepl!)
        server    (jetty/run-jetty (http/app) {:port 0 :join? false})
        port      (.getLocalPort (first (.getConnectors server)))]
    (write-port! port)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (index/close!)
                                 (stop-nrepl! nrepl-srv)
                                 (.delete config/port-file))))
    (println "myrag server listening on port" port)
    (.join server)))
