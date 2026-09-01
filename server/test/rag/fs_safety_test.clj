(ns rag.fs-safety-test
  "A guard over the whole server source rather than over one function: otzar
  *reads* the files it indexes and *writes* only its own state under ~/.otzar.
  Nothing in src/ may create, modify, or delete a file at a path that doesn't
  come from rag.config.

  The behaviour is pinned by tests elsewhere — rag.index-test proves an index
  run, a prune, and a clear leave a real tree byte-for-byte intact. What this
  adds is coverage of code that doesn't exist yet: a new namespace that spits
  somewhere it shouldn't fails here, not on someone's disk."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private mutating-calls
  "Call forms that create, write, move, delete, or re-permission a file. Also
  lucene/open, which hands an IndexWriter a directory to write into."
  [#"\(spit\b"
   #"\(\.delete\b" #"\(\.deleteOnExit\b" #"\(io/delete-file\b"
   #"\(\.mkdirs?\b" #"\(\.createNewFile\b" #"\(\.renameTo\b" #"\(\.setLastModified\b"
   #"\(\.setWritable\b" #"\(\.setReadOnly\b"
   #"\(io/copy\b" #"\(FileOutputStream\.\b" #"\(FileWriter\.\b" #"\(RandomAccessFile\.\b"
   #"\(File/createTempFile\b" #"\(File/createTempDirectory\b"
   #"\(Files/(?:write|writeString|delete|deleteIfExists|move|copy|createFile|createDirectory|createDirectories|createTempFile|createTempDirectory|setPosixFilePermissions|setAttribute|setLastModifiedTime)\b"
   #"\(lucene/open\b"
   ;; Shelling out writes wherever the command does, and no path in the source
   ;; would show it. The server has no reason to spawn a process at all.
   #"\(ProcessBuilder\.\b" #"\(\.exec\b" #"\(shell/sh\b"])

(def ^:private otzar-state
  "A mutating call is fine when its target is one of otzar's own paths: anything
  from rag.config (~/.otzar and the files in it) or the locals built from it —
  config.clj's `otzar-dir`, main.clj's `.nrepl-port` in the server's own working
  directory, and the `otzar-state-file`/`otzar-state-tmp` pair inside
  config/spit-atomic!, which are the caller's config path and the sibling temp
  file renamed over it. Those names exist to be visible here: a local is all this
  line-based scan can see, so the write has to say whose state it is."
  [#"\bconfig/" #"\botzar-dir\b" #"\bnrepl-port-file\b" #"\botzar-state-(?:file|tmp)\b"])

(defn- clj-sources []
  (->> (io/file "src")
       file-seq
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".clj")))))

(defn- writes-outside-otzar
  "Lines in `file` that mutate the filesystem without naming an otzar path."
  [^java.io.File file]
  (->> (str/split-lines (slurp file))
       (map-indexed (fn [i line] [(inc i) line]))
       (filter (fn [[_ line]] (some #(re-find % line) mutating-calls)))
       (remove (fn [[_ line]] (some #(re-find % line) otzar-state)))
       (map (fn [[n line]] (str (.getPath file) ":" n "  " (str/trim line))))))

(deftest every-write-in-the-server-targets-otzar-s-own-state
  (let [sources (clj-sources)]
    (testing "the scan found the source tree (tests run from server/)"
      (is (seq sources)))
    (let [offenders (mapcat writes-outside-otzar sources)]
      (is (= [] (vec offenders))
          (str "Filesystem write outside ~/.otzar. otzar must never create, modify "
               "or delete a user's file: build the path from rag.config, or — if this "
               "really is otzar's own state — add its name to `otzar-state` here.")))))
