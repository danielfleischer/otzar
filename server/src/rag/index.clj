(ns rag.index
  "Index facade — the thin orchestration layer between the HTTP handlers and the
  Lucene core (rag.index.lucene). It owns the single, lazily-opened index
  component, runs the walk → extract → chunk → add pipeline, and exposes
  search/stats/close. Every Lucene specific lives behind rag.index.lucene; this
  namespace only knows the chunk/hit contract."
  (:require [rag.config :as config]
            [rag.embed :as embed]
            [rag.index.lucene :as lucene]
            [rag.index.walker :as walker]
            [rag.index.extractor :as extractor]
            [rag.index.segmenter :as segmenter]
            [rag.index.chunker :as chunker])
  (:import [java.security MessageDigest]
           [java.util.concurrent Executors Future]))

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
  or nil if the extractor skipped it. All of the file's chunks are embedded in a
  single batched call to the embedder, and the vector is attached as :embedding."
  [^java.io.File f]
  (when-let [text (extractor/extract f)]
    (let [path   (.getAbsolutePath f)
          doc-id (sha1 path)
          mtime  (.lastModified f)
          ext    (walker/ext f)
          chunks (chunker/pack (segmenter/segment text))
          vecs   (embed/embed-documents (.getName f) (map :text chunks))]
      (mapv (fn [{:keys [chunk_idx text]} embedding]
              {:id        (str doc-id ":" chunk_idx)
               :doc_id    doc-id
               :path      path
               :ext       ext
               :mtime     mtime
               :chunk_idx chunk_idx
               :text      text
               :embedding embedding})
            chunks vecs))))

(defn- embed-file-result
  "Run the extract+chunk+embed work for one file (the Ollama-bound part we want
  off the writer thread). Never throws — the result is a map tagged with the
  source file so the writer thread can attribute it:
    {:file f :chunks <seq>}   ok; empty/nil chunks means the extractor skipped it
    {:file f :error <msg>}    extract or embed threw"
  [^java.io.File f]
  (try {:file f :chunks (file->chunks f)}
       (catch Exception e {:file f :error (.getMessage e)})))

(defn index-paths!
  "Walk `paths`, extract + chunk + embed each file, and add all chunks to the
  index. The extract+embed work runs through a bounded pool of
  `config/index-concurrency` workers (it is Ollama-bound and parallelizes well);
  results are drained on this thread — in file-submission order — and written
  through the single, thread-safe IndexWriter, with one commit at the end.
  At most `index-concurrency` files are in flight, so memory stays bounded.
  Returns {:indexed <files> :chunks <n> :skipped <files> :errors [{:path ..}]}."
  [paths {:keys [recursive] :or {recursive true}}]
  (let [idx    (ensure!)
        files  (distinct (mapcat #(walker/walk % {:recursive recursive}) paths))
        pool   (Executors/newFixedThreadPool config/index-concurrency)
        submit (fn [^java.io.File f]
                 (.submit pool ^Callable (fn [] (embed-file-result f))))]
    (try
      (loop [in-flight (into clojure.lang.PersistentQueue/EMPTY
                            (map submit (take config/index-concurrency files)))
             pending   (drop config/index-concurrency files)
             indexed 0, chunks 0, skipped 0, errors []]
        (if-let [fut (peek in-flight)]
          (let [{:keys [^java.io.File file error] cs :chunks} (.get ^Future fut)
                ;; keep the window full: as each result lands, submit the next file
                in-flight (cond-> (pop in-flight)
                            (seq pending) (conj (submit (first pending))))
                pending   (if (seq pending) (rest pending) pending)]
            (cond
              error
              (recur in-flight pending indexed chunks skipped
                     (conj errors {:path (.getAbsolutePath file) :error error}))
              (seq cs)
              (do (lucene/add-chunks! idx cs)
                  (recur in-flight pending (inc indexed) (+ chunks (count cs)) skipped errors))
              :else
              (recur in-flight pending indexed chunks (inc skipped) errors)))
          (do (lucene/commit! idx)
              {:indexed indexed :chunks chunks :skipped skipped :errors errors})))
      (finally (.shutdown pool)))))

(defn search
  "Search the index. mode \"keyword\" → BM25 over the text field; mode \"vector\"
  → embed the query and run kNN over the embedding field. \"hybrid\" (RRF over
  both) lands in M4. Returns {:hits [...]}."
  [{:keys [q k mode] :or {k 10 mode "keyword"}}]
  {:hits (case mode
           "keyword" (lucene/keyword-search (ensure!) q k)
           "vector"  (lucene/vector-search (ensure!) (embed/embed-query q) k)
           (throw (ex-info (str "mode \"" mode "\" not supported yet (have: keyword, vector)")
                           {:type :unsupported-mode :mode mode})))})

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
