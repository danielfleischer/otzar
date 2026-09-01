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

(defn remove-roots!
  "paths:[str] -> {:removed [str], :roots [{:path :recursive}]}
  Forget `paths` — stop the watch sweep re-scanning them — and return which ones
  were actually recorded (:removed, sorted) alongside the roots that remain. Naming
  a path that isn't a root is not an error; it just doesn't appear in :removed, so
  the caller can tell the user nothing matched.

  Deliberately unvalidated: forgetting a root whose directory has been deleted or
  unmounted is the main reason to reach for this. It touches roots.edn only — the
  chunks already indexed under those paths stay in the index, and `otzar admin
  prune` is what drops the ones whose files are gone."
  [paths]
  (let [drop?    (set (map str paths))
        current  (read-roots)
        removed  (vec (sort (filter drop? (map :path current))))
        kept     (vec (remove (comp drop? :path) current))]
    (when (seq removed)
      (write-roots! kept))
    {:removed removed :roots kept}))

(defn forget-all!
  "-> {:removed [str]}
  Drop every recorded root, returning the paths that were forgotten (sorted).
  Backs `otzar admin clear --forget-roots`: a wipe of the index leaves roots.edn
  pointing at trees nothing is indexed from any more, and the watch sweep would
  quietly re-index them all on its next tick."
  []
  (let [removed (vec (sort (map :path (read-roots))))]
    (when (seq removed)
      (write-roots! []))
    {:removed removed}))
