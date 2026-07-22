(ns rag.http-test
  "Tests for the Ring/reitit route surface. The rag.index facade is stubbed, so
  these exercise routing, request parsing, validation, status codes, and the
  error->JSON mapping — not Lucene."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [jsonista.core :as j]
            [ring.core.protocols :as rcp]
            [rag.http :as http]
            [rag.index :as index]))

(def ^:private mapper (j/object-mapper {:decode-key-fn keyword}))

(defn- req
  "A Ring request map. `body`, if given, is JSON-encoded onto :body (a String,
  which http/read-body parses)."
  ([method uri] (req method uri nil))
  ([method uri body]
   (cond-> {:request-method method :uri uri}
     (some? body) (assoc :body (j/write-value-as-string body)))))

(defn- call [request]
  ((http/app) request))

(defn- json-body [resp]
  (j/read-value (:body resp) mapper))

(deftest health-ok
  (let [resp (call (req :get "/health"))]
    (is (= 200 (:status resp)))
    (is (= "ok" (:status (json-body resp))))))

(deftest stats-delegates-to-index
  (with-redefs [index/stats (constantly {:num_docs 3 :num_chunks 42})]
    (let [resp (call (req :get "/stats"))]
      (is (= 200 (:status resp)))
      (is (= {:num_docs 3 :num_chunks 42} (json-body resp))))))

(deftest paths-delegates-to-index
  (with-redefs [index/paths (constantly {:count 1 :paths ["/a.md"]})]
    (let [resp (call (req :get "/paths"))]
      (is (= 200 (:status resp)))
      (is (= {:count 1 :paths ["/a.md"]} (json-body resp))))))

(deftest query-requires-non-blank-q
  (testing "blank q is rejected before touching the index"
    (with-redefs [index/search (fn [_] (throw (AssertionError. "should not search")))]
      (let [resp (call (req :post "/query" {:q "   " :k 5}))]
        (is (= 400 (:status resp)))
        (is (str/includes? (:error (json-body resp)) "non-blank"))))))

(deftest query-delegates-to-index
  (with-redefs [index/search (fn [{:keys [q k mode]}]
                               {:echo {:q q :k k :mode mode} :hits [{:id "h1"}]})]
    (let [resp (call (req :post "/query" {:q "hello" :k 5 :mode "keyword"}))
          body (json-body resp)]
      (is (= 200 (:status resp)))
      (is (= {:q "hello" :k 5 :mode "keyword"} (:echo body)))
      (is (= [{:id "h1"}] (:hits body))))))

(deftest document-found-and-missing
  (with-redefs [index/document (fn [id] (when (= id "known") {:id id :text "hi"}))]
    (testing "known id -> 200 with the document"
      (let [resp (call (req :get "/document/known"))]
        (is (= 200 (:status resp)))
        (is (= "hi" (:text (json-body resp))))))
    (testing "unknown id -> 404"
      (let [resp (call (req :get "/document/nope"))]
        (is (= 404 (:status resp)))))))

(deftest clear-and-prune-delegate
  (with-redefs [index/clear! (constantly {:removed_docs 2 :removed_chunks 9})
                index/prune! (fn [{:keys [dry-run]}] {:removed [] :kept 5 :dry_run dry-run})]
    (testing "DELETE /index wipes"
      (let [resp (call (req :delete "/index"))]
        (is (= 200 (:status resp)))
        (is (= 2 (:removed_docs (json-body resp))))))
    (testing "POST /prune passes dry_run through"
      (let [resp (call (req :post "/prune" {:dry_run true}))]
        (is (= 200 (:status resp)))
        (is (true? (:dry_run (json-body resp))))))))

(deftest index-requires-non-empty-paths
  (with-redefs [index/index-paths! (fn [& _] (throw (AssertionError. "should not index")))]
    (let [resp (call (req :post "/index" {:paths []}))]
      (is (= 400 (:status resp)))
      (is (str/includes? (:error (json-body resp)) "paths")))))

(deftest index-streams-ndjson
  (testing "the index route streams start/result events as newline-delimited JSON"
    (with-redefs [index/index-paths!
                  (fn [_paths _opts _cancelled? emit]
                    (emit {:type "start" :total 1})
                    {:indexed 1 :chunks 3 :skipped 0 :unchanged 0 :errors []})]
      (let [resp (call (req :post "/index" {:paths ["/a.md"]}))
            out  (java.io.ByteArrayOutputStream.)]
        (is (= 200 (:status resp)))
        (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"])))
        (rcp/write-body-to-stream (:body resp) resp out)
        (let [lines  (remove str/blank? (str/split-lines (str out)))
              events (mapv #(j/read-value (str/trim %) mapper) lines)]
          (is (= "start" (:type (first events))))
          (is (= {:type "result" :result {:indexed 1 :chunks 3 :skipped 0
                                          :unchanged 0 :errors []}}
                 (last events))))))))

(deftest query-dimension-mismatch-is-400-with-remediation
  (testing "a dimension-mismatch ex-info from search maps to 400, message + data intact"
    (with-redefs [index/search (fn [_]
                                 (throw (ex-info "dim changed\n  myrag admin clear\n  myrag index <paths…>"
                                                 {:type :dimension-mismatch :indexed 1024 :configured 768})))]
      (let [resp (call (req :post "/query" {:q "x" :mode "vector"}))
            body (json-body resp)]
        (is (= 400 (:status resp)))
        (is (str/includes? (:error body) "myrag admin clear"))
        (is (= "dimension-mismatch" (get-in body [:data :type])))))))

(deftest index-dimension-mismatch-is-a-stream-error-event
  (testing "a mismatch during indexing arrives as the terminal NDJSON error event"
    (with-redefs [index/index-paths!
                  (fn [& _] (throw (ex-info "dim changed\n  myrag admin clear" {:type :dimension-mismatch})))]
      (let [resp (call (req :post "/index" {:paths ["/a.md"]}))
            out  (java.io.ByteArrayOutputStream.)]
        (rcp/write-body-to-stream (:body resp) resp out)
        (let [events (->> (str/split-lines (str out))
                          (remove str/blank?)
                          (mapv #(j/read-value (str/trim %) mapper)))]
          (is (= "error" (:type (last events))))
          (is (str/includes? (:error (last events)) "myrag admin clear")))))))

(deftest unknown-route-404
  (is (= 404 (:status (call (req :get "/no-such-route"))))))

(deftest ex-info-maps-to-400-other-throwable-to-500
  (testing "with-errors: ex-info -> 400 (bad request), anything else -> 500"
    (with-redefs [index/stats (fn [] (throw (ex-info "bad thing" {:type :oops})))]
      (let [resp (call (req :get "/stats"))]
        (is (= 400 (:status resp)))
        (is (= "bad thing" (:error (json-body resp))))))
    (with-redefs [index/stats (fn [] (throw (RuntimeException. "boom")))]
      (let [resp (call (req :get "/stats"))]
        (is (= 500 (:status resp)))
        (is (= "boom" (:error (json-body resp))))))))
