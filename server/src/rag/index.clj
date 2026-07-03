(ns rag.index
  "Index facade — the thin orchestration layer between the HTTP handlers and the
  Lucene core (rag.index.lucene). It owns the single, lazily-opened index
  component, runs the walk → extract → chunk → add pipeline, and exposes
  search/stats/close. Every Lucene specific lives behind rag.index.lucene; this
  namespace only knows the chunk/hit contract."
  (:require [rag.config :as config]
            [rag.index.lucene :as lucene]
            [rag.index.walker :as walker]
            [rag.index.extractor :as extractor]
            [rag.index.segmenter :as segmenter]
            [rag.index.chunker :as chunker])
  (:import [java.security MessageDigest]))

(defonce ^:private component (atom nil))

(defn- open? [] (some? @component))

(defn ensure!
  "Return the open Lucene component, opening it under ~/.myrag/index on first use.
  Thread-safe: concurrent callers race to open, and the loser's value is dropped."
  []
  (or @component
      (do (.mkdirs config/index-dir)
          (swap! component (fn [c] (or c (lucene/open config/index-dir)))))))

(defn- sha1 [^String s]
  (let [md (MessageDigest/getInstance "SHA-1")]
    (->> (.digest md (.getBytes s "UTF-8"))
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str))))

(defn- file->chunks
  "Turn one file into a seq of chunk maps (the shape rag.index.lucene expects),
  or nil if the extractor skipped it."
  [^java.io.File f]
  (when-let [text (extractor/extract f)]
    (let [path   (.getAbsolutePath f)
          doc-id (sha1 path)
          mtime  (.lastModified f)
          ext    (walker/ext f)]
      (for [{:keys [chunk_idx text]} (chunker/pack (segmenter/segment text))]
        {:id        (str doc-id ":" chunk_idx)
         :doc_id    doc-id
         :path      path
         :ext       ext
         :mtime     mtime
         :chunk_idx chunk_idx
         :text      text}))))

(defn index-paths!
  "Walk `paths`, extract + chunk each file, and add all chunks to the index.
  Returns {:indexed <files> :chunks <n> :skipped <files> :errors [{:path ..}]}."
  [paths {:keys [recursive] :or {recursive true}}]
  (let [idx   (ensure!)
        files (distinct (mapcat #(walker/walk % {:recursive recursive}) paths))]
    (loop [files files, indexed 0, chunks 0, skipped 0, errors []]
      (if-let [^java.io.File f (first files)]
        (let [cs (try (file->chunks f)
                      (catch Exception e {::error (.getMessage e)}))]
          (cond
            (::error cs)
            (recur (rest files) indexed chunks skipped
                   (conj errors {:path (.getAbsolutePath f) :error (::error cs)}))
            (seq cs)
            (do (lucene/add-chunks! idx cs)
                (recur (rest files) (inc indexed) (+ chunks (count cs)) skipped errors))
            :else
            (recur (rest files) indexed chunks (inc skipped) errors)))
        (do (lucene/commit! idx)
            {:indexed indexed :chunks chunks :skipped skipped :errors errors})))))

(defn search
  "Keyword (BM25) search for M1. Any `mode` other than \"keyword\" is rejected
  until vector/hybrid land (M3/M4). Returns {:hits [...]}."
  [{:keys [q k mode] :or {k 10 mode "keyword"}}]
  (when-not (= mode "keyword")
    (throw (ex-info (str "mode \"" mode "\" not supported yet (M1 is keyword-only)")
                    {:type :unsupported-mode :mode mode})))
  {:hits (lucene/keyword-search (ensure!) q k)})

(defn stats
  "Index counts, or zeros if nothing has opened the index yet."
  []
  (if (open?)
    (lucene/stats @component)
    {:num_docs 0 :num_chunks 0}))

(defn close!
  "Close and forget the index component. No-op if never opened."
  []
  (when-let [c @component]
    (lucene/close c)
    (reset! component nil)))
