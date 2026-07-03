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
                 (.timeout (Duration/ofSeconds 60))
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

(defn embed-batch
  "Embed a seq of strings in a single request; return a vector of float-arrays,
  one per input, in the same order. Empty input → [] (no request made)."
  [texts]
  (let [texts (vec texts)]
    (if (empty? texts)
      []
      (mapv ->floats (:embeddings (post-embed texts))))))

(defn embed-one
  "Embed a single string; return one float-array."
  ^floats [text]
  (first (embed-batch [text])))
