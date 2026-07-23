(ns rag.index.walker
  "Filesystem traversal for indexing. Given a path, yields the individual files
  we should index — expanding directories (optionally recursively) and skipping
  hidden entries, known noise directories, and files no extractor claims.
  Binary/oversized filtering happens later, in the extractor.

  Which extensions count is not decided here: it comes from
  `rag.index.extractor/supported-extensions`, so registering a format there is
  enough to make the walker yield it."
  (:require [clojure.java.io :as io]
            [rag.index.extractor :as extractor]))

(def skip-dirs
  "Directory names never descended into during a recursive walk."
  #{".git" ".hg" ".svn" "node_modules" "target" "dist" "build" "out"
    ".clj-kondo" ".lsp" ".cpcache" "__pycache__" ".idea" ".vscode"
    ".gradle" ".mvn" "vendor" ".otzer"})

(defn indexable-file?
  "File -> bool
  True when `f` is a non-hidden regular file whose extension some format in the
  extractor registry claims."
  [^java.io.File f]
  (and (.isFile f)
       (not (.isHidden f))
       (contains? extractor/supported-extensions (extractor/ext f))))

(defn- skip-dir? [^java.io.File d]
  (or (.isHidden d)
      (contains? skip-dirs (.getName d))))

(defn walk
  "str|File, {:recursive bool} -> (seq File)
  Return a lazy seq of indexable java.io.File values under `path`.
  A path that is itself a file is yielded when it's indexable; a directory is
  expanded, with `:recursive` controlling whether subdirectories are descended.
  Nonexistent paths yield nothing."
  [path {:keys [recursive] :or {recursive true}}]
  (let [root (io/file path)]
    (cond
      (not (.exists root)) []
      (.isFile root)       (if (indexable-file? root) [root] [])
      (.isDirectory root)
      (let [entries (sort (or (seq (.listFiles root)) []))]
        (mapcat (fn [^java.io.File e]
                  (cond
                    (indexable-file? e)
                    [e]
                    (and recursive (.isDirectory e) (not (skip-dir? e)))
                    (walk e {:recursive true})
                    :else []))
                entries))
      :else [])))
