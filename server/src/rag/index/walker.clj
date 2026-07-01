(ns rag.index.walker
  "Filesystem traversal for indexing. Given a path, yields the individual files
  we should index — expanding directories (optionally recursively) and skipping
  hidden entries, known noise directories, and files whose extension isn't
  text-like. Binary/oversized filtering happens later, in the extractor."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def text-extensions
  "Extensions treated as indexable text for v1 (M1). PDF/HTML via Tika is M8."
  #{"txt" "text" "md" "markdown" "org" "rst" "adoc" "tex"
    "clj" "cljc" "cljs" "cljd" "edn" "bb"
    "go" "py" "rs" "java" "kt" "kts" "scala"
    "js" "jsx" "ts" "tsx" "mjs" "cjs"
    "c" "h" "cc" "cpp" "hpp" "cxx" "hxx"
    "rb" "php" "pl" "sh" "bash" "zsh" "fish" "lua" "el" "vim"
    "json" "yaml" "yml" "toml" "ini" "cfg" "conf" "properties"
    "html" "htm" "css" "scss" "less" "sql" "xml" "csv" "tsv"})

(def skip-dirs
  "Directory names never descended into during a recursive walk."
  #{".git" ".hg" ".svn" "node_modules" "target" "dist" "build" "out"
    ".clj-kondo" ".lsp" ".cpcache" "__pycache__" ".idea" ".vscode"
    ".gradle" ".mvn" "vendor" ".myrag"})

(defn ext
  "Lower-cased extension of `f` (without the dot), or nil if it has none."
  [^java.io.File f]
  (let [n (.getName f)
        i (.lastIndexOf n ".")]
    (when (pos? i)
      (str/lower-case (subs n (inc i))))))

(defn indexable-file?
  "True when `f` is a non-hidden regular file with a text-like extension."
  [^java.io.File f]
  (and (.isFile f)
       (not (.isHidden f))
       (contains? text-extensions (ext f))))

(defn- skip-dir? [^java.io.File d]
  (or (.isHidden d)
      (contains? skip-dirs (.getName d))))

(defn walk
  "Return a lazy seq of indexable java.io.File values under `path`.
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
