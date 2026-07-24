(ns rag.embed
  "Embedding client — turns text into dense vectors via an Ollama sidecar
  (plan §3.4). This namespace is the *entire* embedding boundary: the indexing
  pipeline (rag.index) and the query path call `embed-batch`/`embed-one` and never
  speak HTTP or know which model is behind it. Swapping to an in-process backend
  (DJL/ONNX) later means reimplementing these two fns and nothing else.

  Ollama endpoint (verified working):
    POST {OLLAMA_URL}/api/embed  {\"model\": m, \"input\": <string | [strings]>}
      → {\"embeddings\": [[float ...], ...]}   one row per input, in request order

  Vectors come back as float-arrays so they drop straight into Lucene's
  KnnFloatVectorField (rag.index.lucene). The vector dimension is whatever the
  model emits (bge-m3 → 1024); nothing here hardcodes it."
  (:require [clojure.string :as str]
            [jsonista.core :as j]
            [rag.config :as config])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util.concurrent Callable Executors Future]))

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 5))
             (.build))))

(def ^:private mapper (j/object-mapper {:decode-key-fn keyword}))

(def ^:private embed-pool
  "Shared, fixed-size pool of embed workers — the global budget of concurrent
  Ollama calls (config/embed-concurrency). Every batch, whether from many files or
  from the many batches of one large file, is dispatched through this one pool, so
  a single big file parallelizes without over-subscribing Ollama and a folder of
  files still totals at most config/embed-concurrency in-flight requests. Opened on
  first use and kept for the server's lifetime (a fixed pool parks idle threads).

  NB: against Ollama this currently yields *no* speedup — Ollama loads embedding
  models single-slot (llama-server `-np 1`) regardless of OLLAMA_NUM_PARALLEL, so
  concurrent embed requests serialize server-side, and the embedding model is
  compute-bound anyway. The concurrency is kept for a future backend that
  serves embeddings with real parallelism (multiple slots, an in-process DJL/ONNX
  batcher, or sharded Ollama). Today's real levers are a faster model or fewer/
  larger chunks, not request concurrency."
  (delay (Executors/newFixedThreadPool config/embed-concurrency)))

(defn- post-embed
  "POST `inputs` (a vector of strings) to Ollama /api/embed; return the parsed
  response body (a keyword-keyed map). Throws ex-info on a non-200 status."
  [inputs]
  (let [body (j/write-value-as-string {:model config/embed-model :input inputs})
        req  (-> (HttpRequest/newBuilder (URI/create (str config/ollama-url "/api/embed")))
                 (.timeout (Duration/ofSeconds config/embed-timeout-s))
                 (.header "Content-Type" "application/json")
                 (.POST (HttpRequest$BodyPublishers/ofString body))
                 (.build))
        resp (try
               (.send ^HttpClient @client req (HttpResponse$BodyHandlers/ofString))
               (catch java.io.IOException e
                 (throw (ex-info (str "Cannot reach Ollama at " config/ollama-url
                                      " — is `ollama serve` running? (" (.getMessage e) ")")
                                 {:type :embed-unreachable :url config/ollama-url}
                                 e))))]
    (if (= 200 (.statusCode resp))
      (j/read-value (.body resp) mapper)
      (throw (ex-info (str "Ollama embed request failed (HTTP " (.statusCode resp)
                           ") — is model \"" config/embed-model "\" pulled?")
                      {:type :embed-error
                       :status (.statusCode resp)
                       :body (.body resp)})))))

(defn- ollama-get
  "GET `path` on the Ollama server; parsed keyword-keyed body, or nil if the
  request is non-200 or the server is unreachable. Model metadata is best-effort:
  a failure degrades the stored spec, it doesn't break indexing."
  [path]
  (try
    (let [req  (-> (HttpRequest/newBuilder (URI/create (str config/ollama-url path)))
                   (.timeout (Duration/ofSeconds 10))
                   (.GET)
                   (.build))
          resp (.send ^HttpClient @client req (HttpResponse$BodyHandlers/ofString))]
      (when (= 200 (.statusCode resp))
        (j/read-value (.body resp) mapper)))
    (catch java.io.IOException _ nil)))

(defn- ollama-show
  "POST /api/show for `model`; parsed body or nil (best-effort, like ollama-get)."
  [model]
  (try
    (let [body (j/write-value-as-string {:model model})
          req  (-> (HttpRequest/newBuilder (URI/create (str config/ollama-url "/api/show")))
                   (.timeout (Duration/ofSeconds 10))
                   (.header "Content-Type" "application/json")
                   (.POST (HttpRequest$BodyPublishers/ofString body))
                   (.build))
          resp (.send ^HttpClient @client req (HttpResponse$BodyHandlers/ofString))]
      (when (= 200 (.statusCode resp))
        (j/read-value (.body resp) mapper)))
    (catch java.io.IOException _ nil)))

(defn- ->floats ^floats [row]
  (float-array (map float row)))

;; bge-m3 is symmetric — documents and queries are embedded as raw text, no task
;; prompts. (Some models, e.g. EmbeddingGemma, need asymmetric document/query
;; prompts for the two vector spaces to line up; if OTZER_EMBED_MODEL changes to
;; such a model, reintroduce per-side prompting in embed-documents/embed-query.)

(defn- embed-prepared
  "Embed a seq of already-prompt-prefixed strings, split into requests of at most
  config/embed-batch inputs so no single Ollama call represents an unbounded
  amount of work. `cancelled?` is polled per batch; once it turns true the
  embedding aborts (throwing ::cancelled) instead of doing the remaining batches —
  this is how a disconnected client stops Ollama promptly, even partway through a
  large file. After each batch `on-batch` is called with [cumulative-done total] —
  how many of this file's inputs are embedded so far and how many there are in all
  — so a caller can render a chunk-level progress bar *within* a large file (the
  file-level callback in rag.index only fires once the whole file is done). Returns
  a vector of float-arrays, one per input, in order; empty input → [] with no
  request made.

  The batches are dispatched across @embed-pool, so up to config/embed-concurrency
  of them embed *concurrently* — the batches of a single large file parallelize,
  not just different files. `embed-one-batch` is the thread-safe unit of work
  (guard cancellation, call Ollama, report progress, return this batch's vectors in
  order); safe to run on many pool threads at once because post-embed uses the
  shared thread-safe HttpClient, `done` is an atom, and on-batch → the client's emit
  is locked. All batches are submitted up front, then gathered in *submission
  order* — so vectors come back batch-0-first regardless of which finished first,
  which rag.index relies on to map vectors positionally onto chunks. The first
  failed batch (e.g. a cancelled one throwing ::cancelled) is propagated, and the
  siblings still in flight are cancelled so we don't keep Ollama busy on work whose
  result is being discarded."
  [prepared cancelled? on-batch]
  (let [prepared (vec prepared)
        total    (count prepared)
        batches  (vec (partition-all config/embed-batch prepared))
        done     (atom 0)
        embed-one-batch
        (fn [batch]
          (when (cancelled?)
            (throw (ex-info "embedding cancelled" {:type ::cancelled})))
          (let [vs (mapv ->floats (:embeddings (post-embed (vec batch))))]
            (on-batch (swap! done + (count batch)) total)
            vs))
        ;; reify Callable (not a bare fn, which is *also* Runnable) so .submit binds
        ;; the Callable overload — the Runnable one's Future.get returns nil, which
        ;; would silently drop every batch's vectors.
        futs (mapv (fn [batch]
                     (.submit @embed-pool (reify Callable (call [_] (embed-one-batch batch)))))
                   batches)]
    (try
      (into [] (mapcat (fn [^Future f] (.get f)) futs))
      (catch java.util.concurrent.ExecutionException e
        (run! #(.cancel ^Future % true) futs)     ; stop siblings still embedding
        (throw (or (.getCause e) e))))))           ; surface the batch's own ex-info

(defn embed-documents
  "texts:[str], cancelled?:(->bool), on-batch:(fn[done total]) -> [float-array]
  Embed chunk texts for *storage* (indexing side). `cancelled?` (default never) is
  polled between batches so an in-progress file can be abandoned when the client
  disconnects. `on-batch` (default no-op) is called [done total] after each batch
  for chunk-level progress. Returns a vector of float-arrays, one per input, in order."
  ([texts] (embed-documents texts (constantly false) (fn [_ _])))
  ([texts cancelled?] (embed-documents texts cancelled? (fn [_ _])))
  ([texts cancelled? on-batch]
   (embed-prepared texts cancelled? on-batch)))

(defn embed-query
  "str -> float-array
  Embed a single search string for *retrieval*. Returns one float-array."
  ^floats [text]
  (first (embed-prepared [text] (constantly false) (fn [_ _]))))

(defn- tag-digest
  "First 12 hex of Ollama's manifest sha256 for `tag`, from /api/tags, or nil.
  This is the model's real version: re-pulling or repointing a tag changes the
  digest even when the name is unchanged. Matches an exact tag, else `<name>:latest`,
  else any tag sharing the base name."
  [tag]
  (let [base   (first (str/split tag #":"))
        models (:models (ollama-get "/api/tags"))
        match  (or (first (filter #(= tag (:name %)) models))
                   (first (filter #(= (str base ":latest") (:name %)) models))
                   (first (filter #(str/starts-with? (str (:name %)) (str base ":")) models)))
        digest (some-> (:digest match) (str/replace #"^sha256:" ""))]
    (when digest (subs digest 0 (min 12 (count digest))))))

(defn- model-dim
  "Embedding dimension of `model` from /api/show's model_info (the
  `<arch>.embedding_length` entry), or nil if Ollama doesn't report it."
  [model]
  (some (fn [[k v]] (when (str/ends-with? (name k) ".embedding_length") v))
        (:model_info (ollama-show model))))

;; Model metadata is stable for a process — config/embed-model is read once at
;; startup, and a model's embedding dimension is intrinsic to its architecture — so
;; the /api/tags + /api/show probes behind embedding-dim/model-spec are cached for
;; the process lifetime rather than re-run on every index (and every 30-minute watch
;; sweep). Only a *complete* probe is cached: a degraded result from an
;; Ollama-not-yet-up boot must be retried, not frozen in, or the index would stamp
;; chunks with a bare model name and the dim gate stay disabled even once Ollama is
;; back. Caveat: re-pulling the model in place (a new digest) while the server runs
;; is not noticed until a restart calls reset-model-cache!; a deliberate model change
;; is a restart anyway, and `otzer admin reindex --force` re-embeds regardless.
(def ^:private dim-cache  (atom nil))
(def ^:private spec-cache (atom nil))

(defn reset-model-cache!
  "-> nil
  Forget the cached model dimension and spec so the next call re-probes Ollama.
  Called on nothing today (the cache is process-lifetime by design); a hook for
  tests and for a future in-place re-pull that must be picked up without a restart."
  []
  (reset! dim-cache nil)
  (reset! spec-cache nil)
  nil)

(defn embedding-dim
  "-> long | nil
  The embedding dimension the *currently configured* model produces, per Ollama's
  /api/show metadata, or nil when Ollama doesn't report it (or is unreachable).
  Lets rag.index detect a model dimension change against the index up front —
  before embedding a whole tree — since Lucene can't mix vector dimensions in one
  field. Best-effort by design: a nil just means the pre-check is skipped and any
  real mismatch surfaces at the Lucene write instead.

  Cached once known (see the cache note above): a nil is retried on the next call
  (Ollama may not have been up yet), a real dimension is served without re-probing."
  []
  (or @dim-cache
      (let [d (model-dim config/embed-model)]
        (when d (reset! dim-cache d))
        d)))

(defn model-spec
  "-> str
  A stable identity for the *currently configured* embedding model, stored on each
  indexed chunk so a reindex can tell whether a chunk's vector still comes from the
  same model. Format:
    \"<model>@<digest>/<dim>d\"   e.g. \"bge-m3@85462619ee72/1024d\"
  <digest> is the Ollama manifest hash (the real version — survives a rename, bumps
  on a re-pull), <dim> the embedding dimension (a change here means the whole Lucene
  vector field must be rebuilt, not just re-embedded). Any part Ollama won't report
  is omitted; if it's unreachable for metadata this degrades to the bare model name,
  so indexing still runs. Compared by plain string equality (rag.index/file->chunks).

  Cached once *complete* (both digest and dim resolved): repeat calls — every index
  run and every watch sweep — are served without re-probing Ollama. A degraded
  (partial) spec is returned but not cached, so it is retried once Ollama is up."
  []
  (or @spec-cache
      (let [digest (tag-digest config/embed-model)
            dim    (embedding-dim)   ; shares the dim cache
            spec   (str config/embed-model
                        (when digest (str "@" digest))
                        (when dim (str "/" dim "d")))]
        (when (and digest dim) (reset! spec-cache spec))
        spec)))
