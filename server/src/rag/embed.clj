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
  (:require [jsonista.core :as j]
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
  "Embed a seq of already-prompt-prefixed strings in one request; return a vector
  of float-arrays, one per input, in order. Empty input → [] (no request made)."
  [prepared]
  (let [prepared (vec prepared)]
    (if (empty? prepared)
      []
      (mapv ->floats (:embeddings (post-embed prepared))))))

(defn embed-documents
  "Embed chunk texts for *storage* (indexing side), applying the document prompt
  with `title` (the source file name) so the vectors carry a little document
  context. Returns a vector of float-arrays, one per input, in order."
  [title texts]
  (embed-prepared (map #(document-prompt title %) texts)))

(defn embed-query
  "Embed a single search string for *retrieval*, applying the query prompt.
  Returns one float-array."
  ^floats [text]
  (first (embed-prepared [(str query-prompt text)])))
