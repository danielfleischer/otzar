(ns rag.embed-test
  "Unit tests for the embedding boundary. Ollama HTTP is stubbed — post-embed for
  the batching/ordering/cancellation logic, and ollama-get/ollama-show for the
  model-spec metadata parsing — so nothing here talks to a real sidecar."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [rag.config :as config]
            [rag.embed :as embed]))

;; The model dimension/spec probes are cached for the process lifetime, so isolate
;; each test — a complete probe in one would otherwise be served to the next.
(use-fixtures :each (fn [t] (embed/reset-model-cache!) (t) (embed/reset-model-cache!)))

;; A stand-in Ollama /api/embed that echoes each input's numeric value back as a
;; 1-element vector, so a test can assert the vectors come back in input order.
(defn- echo-embed [inputs]
  {:embeddings (mapv (fn [s] [(Double/parseDouble s)]) inputs)})

(defn- firsts [vectors]
  (mapv #(first (vec %)) vectors))

(deftest embed-documents-preserves-order-across-batches
  (testing "vectors return in input order even when split into several batches"
    (with-redefs [config/embed-batch 2
                  embed/post-embed   echo-embed]
      (let [out (embed/embed-documents (mapv str (range 5)))]
        (is (= 5 (count out)))
        (is (= (mapv float [0 1 2 3 4]) (firsts out)))))))

(deftest embed-documents-batches-by-embed-batch
  (testing "input is split into requests of at most config/embed-batch"
    (let [sizes (atom [])]
      (with-redefs [config/embed-batch 2
                    embed/post-embed   (fn [inputs]
                                         (swap! sizes conj (count inputs))
                                         (echo-embed inputs))]
        (embed/embed-documents (mapv str (range 5)))
        ;; batches dispatch concurrently, so compare as a multiset: [2 2 1].
        (is (= [1 2 2] (sort @sizes)))
        (is (= 5 (reduce + @sizes)))))))

(deftest embed-documents-reports-progress
  (testing "on-batch sees cumulative done counts, all against the same total"
    (let [calls (atom [])]
      (with-redefs [config/embed-batch 2
                    embed/post-embed   echo-embed]
        (embed/embed-documents (mapv str (range 5))
                               (constantly false)
                               (fn [done total] (swap! calls conj [done total])))
        (is (every? #(= 5 (second %)) @calls))
        (is (= 5 (apply max (map first @calls))))))))

(deftest embed-documents-empty-makes-no-request
  (testing "no inputs -> [] and post-embed is never called"
    (with-redefs [embed/post-embed (fn [_] (throw (AssertionError. "should not be called")))]
      (is (= [] (embed/embed-documents []))))))

(deftest embed-documents-honors-cancellation
  (testing "a cancelled? that is already true aborts before any HTTP call"
    (with-redefs [embed/post-embed (fn [_] (throw (AssertionError. "should not be called")))]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (embed/embed-documents ["x"] (constantly true))))]
        (is (= ::embed/cancelled (:type (ex-data ex))))))))

(deftest embed-query-returns-single-vector
  (testing "embed-query yields one float-array, not a collection of them"
    (with-redefs [embed/post-embed echo-embed]
      (is (= [(float 7)] (vec (embed/embed-query "7")))))))

(deftest model-spec-formats-model-digest-and-dim
  (testing "model@<digest12>/<dim>d from /api/tags + /api/show"
    (with-redefs [embed/ollama-get  (fn [_]
                                       {:models [{:name   (str config/embed-model ":latest")
                                                  :digest "sha256:85462619ee72abcdef"}]})
                  embed/ollama-show (fn [_] {:model_info {:bert.embedding_length 1024}})]
      (is (= (str config/embed-model "@85462619ee72/1024d")
             (embed/model-spec))))))

(deftest model-spec-degrades-when-ollama-unreachable
  (testing "no metadata -> the bare model name, so indexing still runs"
    (with-redefs [embed/ollama-get  (constantly nil)
                  embed/ollama-show (constantly nil)]
      (is (= config/embed-model (embed/model-spec))))))

(deftest model-spec-caches-a-complete-probe
  (testing "a complete spec is computed once, then served from cache without re-probing"
    (let [probes (atom 0)]
      (with-redefs [embed/ollama-get  (fn [_]
                                        (swap! probes inc)
                                        {:models [{:name   (str config/embed-model ":latest")
                                                   :digest "sha256:85462619ee72"}]})
                    embed/ollama-show (fn [_] {:model_info {:bert.embedding_length 1024}})]
        (dotimes [_ 3] (embed/model-spec))
        (is (= 1 @probes) "Ollama is probed only on the first call")))))

(deftest model-spec-retries-until-a-complete-probe
  (testing "a degraded probe is not cached; a later call re-probes and caches"
    (let [live (atom false)]
      (with-redefs [embed/ollama-get  (fn [_]
                                        (when @live
                                          {:models [{:name   (str config/embed-model ":latest")
                                                     :digest "sha256:85462619ee72"}]}))
                    embed/ollama-show (fn [_] (when @live {:model_info {:bert.embedding_length 1024}}))]
        (is (= config/embed-model (embed/model-spec)))   ; Ollama down: bare name, not cached
        (reset! live true)
        (is (= (str config/embed-model "@85462619ee72/1024d")
               (embed/model-spec)))))))                  ; now complete → cached

(deftest embedding-dim-caches-once-known-and-retries-while-nil
  (testing "a known dimension is cached; a nil (Ollama silent) is retried"
    (let [live   (atom false)
          probes (atom 0)]
      (with-redefs [embed/ollama-show (fn [_]
                                        (swap! probes inc)
                                        (when @live {:model_info {:bert.embedding_length 768}}))]
        (is (nil? (embed/embedding-dim)))                ; not yet up: probe 1, nil, not cached
        (reset! live true)
        (is (= 768 (embed/embedding-dim)))               ; probe 2, resolves and caches
        (embed/embedding-dim)                            ; served from cache, no probe
        (is (= 2 @probes) "re-probed while nil, then cached once known")))))
