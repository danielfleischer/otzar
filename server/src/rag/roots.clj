(ns rag.roots
  "Persisted set of index roots — the paths the user has asked otzar to index,
  saved to ~/.otzar/roots.edn. The Lucene index stores per-file paths, not roots,
  so this is the only record of an *intent* like \"index ~/notes recursively\" as a
  unit. /index records each requested path here (rag.http); the background sweep
  (rag.watch → rag.index/sweep!) reads them back to know what tree to re-scan for
  new, changed, or deleted files.

  Shape on disk: {:roots [{:path \"/abs/path\" :recursive true} ...]}. Paths are
  absolute (the client already resolves them before POST /index) and de-duplicated
  by path — re-indexing a path just updates its :recursive flag."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [rag.config :as config]))

(defn read-roots
  "-> [{:path str :recursive bool}]
  The persisted roots, sorted by path; [] when the file is absent or unreadable
  (a corrupt/hand-edited file degrades to \"no roots\", it doesn't crash the sweep)."
  []
  (or (try
        (when (.exists config/roots-file)
          (:roots (edn/read-string (slurp config/roots-file))))
        (catch Exception _ nil))
      []))

(defn- write-roots! [roots]
  (config/ensure-dir!)
  ;; Atomically, via a temp file and a rename (config/spit-atomic!). read-roots
  ;; degrades a truncated file to "no roots", so a half-written roots.edn would
  ;; silently un-register every tree the user asked to keep indexed.
  (config/spit-atomic! config/roots-file
                       (pr-str {:roots (vec (sort-by :path roots))})))

(defn unusable
  "paths:[str] -> [{:path str :reason str}]
  The entries of `paths` that can't serve as index roots, each with a short reason
  — absent from disk, or present but unreadable. Empty when they are all fine.

  This exists because a root is *persisted*, not just walked: a typo saved to
  roots.edn is not a one-off no-op but a permanent one, and worse, a root that
  isn't on disk is exactly what makes the watch sweep stop pruning (it can't tell
  a typo from an unmounted volume, so it refuses to treat a whole subtree as
  orphaned). Callers check before add-roots!, so nothing that could never index
  gets written down. Removing a root that has since vanished is a separate matter
  — see remove-roots!, which never validates."
  [paths]
  (reduce (fn [bad p]
            (let [f (io/file p)]
              (cond
                (not (.exists f))  (conj bad {:path (str p) :reason "no such file or directory"})
                (not (.canRead f)) (conj bad {:path (str p) :reason "not readable"})
                :else              bad)))
          [] paths))

(defn add-roots!
  "paths:[str], recursive:bool -> [{:path :recursive}]
  Merge `paths` into the persisted root set, each recorded with `recursive`, keyed
  by absolute path (a repeat updates that path's flag). Returns the merged set.
  Called from the /index handler so every manual index is remembered as something
  the sweep should keep re-scanning — which is why that handler runs `unusable`
  over the paths first and refuses the request rather than saving one that can
  never index."
  [paths recursive]
  (let [existing (into {} (map (juxt :path identity)) (read-roots))
        merged   (reduce (fn [m p] (assoc m p {:path p :recursive (boolean recursive)}))
                         existing paths)
        roots    (vec (vals merged))]
    (write-roots! roots)
    roots))
