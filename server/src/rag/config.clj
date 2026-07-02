(ns rag.config
  "Runtime paths shared with the Go client. The contract: the server owns the
  port file (it picks a free port and writes the actual one); the client owns
  the pid file. Both live under ~/.myrag/."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

(def home-dir (System/getProperty "user.home"))
(def myrag-dir (io/file home-dir ".myrag"))
(def port-file (io/file myrag-dir "server.port"))
(def pid-file  (io/file myrag-dir "server.pid"))
(def index-dir (io/file myrag-dir "index"))

(def ^:private owner-only
  "rwx------ — the owner may read/list/enter ~/.myrag; nobody else can."
  (PosixFilePermissions/fromString "rwx------"))

(defn ensure-dir!
  "Create ~/.myrag if absent and lock it to the owner, re-tightening on every
  boot. Everything inside (index/, *.pid, *.port, *.log) inherits the protection:
  with the parent unenterable by others, they can't reach the contents."
  []
  (.mkdirs myrag-dir)
  (Files/setPosixFilePermissions (.toPath myrag-dir) owner-only))
