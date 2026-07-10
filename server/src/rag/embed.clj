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
  model emits (embeddinggemma → 768); nothing here hardcodes it."
  (:require [clojure.string :as str]
            [jsonista.core :as j]
            [rag.config :as config])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 5))
             (.build))))

(def ^:private mapper (j/object-mapper {:decode-key-fn keyword}))

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

;; EmbeddingGemma is trained with asymmetric task prompts — the *same* text must
;; be embedded differently as a stored document vs. a search query, or the two
;; vector spaces don't line up. Formats are verbatim from the model card:
;; https://ai.google.dev/gemma/docs/embeddinggemma/model_card#prompt-instructions
;; (embeddinggemma-specific; revisit these if MYRAG_EMBED_MODEL changes.)
(def ^:private query-prompt "task: search result | query: ")

(defn- document-prompt
  "EmbeddingGemma's document prompt, using the source file name as the title slot."
  [title text]
  (str "title: " title " | text: " text))

(defn- embed-prepared
  "Embed a seq of already-prompt-prefixed strings, split into requests of at most
  config/embed-batch inputs so no single Ollama call represents an unbounded
  amount of work. `cancelled?` is polled before each batch; once it turns true the
  embedding aborts (throwing ::cancelled) instead of dispatching the remaining
  batches — this is how a disconnected client stops Ollama promptly, even partway
  through a large file. After each batch `on-batch` is called with
  [cumulative-done total] — how many of this file's inputs are embedded so far and
  how many there are in all — so a caller can render a chunk-level progress bar
  *within* a large file (the file-level callback in rag.index only fires once the
  whole file is done). Returns a vector of float-arrays, one per input, in order;
  empty input → [] with no request made."
  [prepared cancelled? on-batch]
  (let [total (count prepared)]
    (loop [batches (partition-all config/embed-batch prepared)
           done    0
           acc     (transient [])]
      (if-let [batch (first batches)]
        (do
          (when (cancelled?)
            (throw (ex-info "embedding cancelled" {:type ::cancelled})))
          (let [batch (vec batch)
                acc   (reduce conj! acc (map ->floats (:embeddings (post-embed batch))))
                done  (+ done (count batch))]
            (on-batch done total)
            (recur (rest batches) done acc)))
        (persistent! acc)))))

(defn embed-documents
  "title:str, texts:[str], cancelled?:(->bool), on-batch:(fn[n]) -> [float-array]
  Embed chunk texts for *storage* (indexing side), applying the document prompt
  with `title` (the source file name) so the vectors carry a little document
  context. `cancelled?` (default never) is polled between batches so an in-progress
  file can be abandoned when the client disconnects. `on-batch` (default no-op) is
  called [done total] after each batch for chunk-level progress. Returns a vector
  of float-arrays, one per input, in order."
  ([title texts] (embed-documents title texts (constantly false) (fn [_ _])))
  ([title texts cancelled?] (embed-documents title texts cancelled? (fn [_ _])))
  ([title texts cancelled? on-batch]
   (embed-prepared (map #(document-prompt title %) texts) cancelled? on-batch)))

(defn embed-query
  "str -> float-array
  Embed a single search string for *retrieval*, applying the query prompt.
  Returns one float-array."
  ^floats [text]
  (first (embed-prepared [(str query-prompt text)] (constantly false) (fn [_ _]))))

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

(defn model-spec
  "-> str
  A stable identity for the *currently configured* embedding model, stored on each
  indexed chunk so a reindex can tell whether a chunk's vector still comes from the
  same model. Format:
    \"<model>@<digest>/<dim>d\"   e.g. \"embeddinggemma@85462619ee72/768d\"
  <digest> is the Ollama manifest hash (the real version — survives a rename, bumps
  on a re-pull), <dim> the embedding dimension (a change here means the whole Lucene
  vector field must be rebuilt, not just re-embedded). Any part Ollama won't report
  is omitted; if it's unreachable for metadata this degrades to the bare model name,
  so indexing still runs. Compared by plain string equality (rag.index/file->chunks)."
  []
  (let [digest (tag-digest config/embed-model)
        dim    (model-dim config/embed-model)]
    (str config/embed-model
         (when digest (str "@" digest))
         (when dim (str "/" dim "d")))))
