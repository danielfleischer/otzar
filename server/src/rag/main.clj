(ns rag.main
  "Entry point. Binds Jetty to an ephemeral port (0), discovers the actual port
  from the connector, and publishes it to ~/.myrag/server.port so the Go client
  can find us. The client owns process lifecycle; /shutdown handles exit."
  (:require [rag.http :as http]
            [rag.config :as config]
            [ring.adapter.jetty :as jetty])
  (:gen-class))

(defn- write-port! [port]
  (config/ensure-dir!)
  (spit config/port-file (str port)))

(defn -main [& _args]
  (config/ensure-dir!)
  (let [server (jetty/run-jetty (http/app) {:port 0 :join? false})
        port   (.getLocalPort (first (.getConnectors server)))]
    (write-port! port)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (.delete config/port-file))))
    (println "myrag server listening on port" port)
    (.join server)))
