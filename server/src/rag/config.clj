(ns rag.config
  "Runtime paths shared with the Go client. The contract: the server owns the
  port file (it picks a free port and writes the actual one); the client owns
  the pid file. Both live under ~/.myrag/."
  (:require [clojure.java.io :as io]))

(def home-dir (System/getProperty "user.home"))
(def myrag-dir (io/file home-dir ".myrag"))
(def port-file (io/file myrag-dir "server.port"))
(def pid-file  (io/file myrag-dir "server.pid"))
(def index-dir (io/file myrag-dir "index"))

(defn ensure-dir! []
  (.mkdirs myrag-dir))
