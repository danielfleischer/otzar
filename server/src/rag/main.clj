(ns rag.main
  "Entry point. Binds Jetty to an ephemeral port (0), discovers the actual port
  from the connector, and publishes it to ~/.myrag/server.port so the Go client
  can find us. Also starts a loopback nREPL (see start-nrepl!) so CIDER can attach
  to the *running* server and redefine code live. The client owns process
  lifecycle; /shutdown handles exit."
  (:require [rag.http :as http]
            [rag.config :as config]
            [rag.index :as index]
            [ring.adapter.jetty :as jetty]
            [nrepl.server :as nrepl-server]
            [cider.nrepl :refer [cider-nrepl-handler]])
  (:import [java.io File])
  (:gen-class))

(defn- write-port! [port]
  (config/ensure-dir!)
  (spit config/port-file (str port)))

(def ^:private nrepl-port-file
  "CIDER auto-discovers .nrepl-port in the project root; the server's working dir
  is the server/ dir (see client lifecycle.go), so we drop it there."
  (File. ".nrepl-port"))

(defn -main [& _args]
  (config/ensure-dir!)
  (let [nrepl-srv (let [srv (nrepl-server/start-server
                             :port config/nrepl-port
                             :handler cider-nrepl-handler)
                        port (:port srv)]
                    (spit nrepl-port-file (str port))
                    srv)
        server    (jetty/run-jetty (http/app) {:port 0 :join? false})
        port      (.getLocalPort (first (.getConnectors server)))]
    (write-port! port)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (index/close!)
                                 (when nrepl-srv
                                   (nrepl-server/stop-server nrepl-srv)
                                   (.delete nrepl-port-file))
                                 (.delete config/port-file))))
    (println "myrag server listening on port" port)
    (.join server)))
