(ns rag.http-test
  "Tests for the Ring/reitit route surface. The rag.index facade is stubbed, so
  these exercise routing, request parsing, validation, status codes, and the
  error->JSON mapping — not Lucene."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [jsonista.core :as j]
            [ring.core.protocols :as rcp]
            [rag.config :as config]
            [rag.http :as http]
            [rag.index :as index]
            [rag.roots :as roots]))

(def ^:private mapper (j/object-mapper {:decode-key-fn keyword}))

(def ^:private ^:dynamic *real-file*
  "An absolute path to a real, readable file, set per test by the fixture. POST
  /index refuses paths that aren't on disk — they would be persisted as roots — so
  every test about the index route has to name something that exists."
  nil)

(defn- the-file [] *real-file*)

;; POST /index records the requested paths to config/roots-file (rag.roots).
;; Redirect that to a throwaway temp file — and neuter ensure-dir! so it doesn't
;; re-tighten ~/.otzar — so no test here ever writes the real ~/.otzar/roots.edn.
(use-fixtures :each
  (fn [t]
    (let [tmp  (java.io.File/createTempFile "http-test-roots" ".edn")
          real (doto (java.io.File/createTempFile "http-test-index" ".md")
                 (spit "hello"))]
      (try
        (with-redefs [config/roots-file  tmp
                      config/ensure-dir! (fn [] nil)]
          (binding [*real-file* (.getAbsolutePath real)]
            (t)))
        (finally (.delete tmp) (.delete real))))))

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

(deftest health-reports-version
  ;; The client compares this against its own build; running from source it is
  ;; "dev", which is precisely the value that means "don't compare".
  (let [body (json-body (call (req :get "/health")))]
    (is (string? (:version body)))
    (is (seq (:version body)))))

(deftest health-reports-launch-source
  ;; What `otzar server status` shows instead of its own guess. The test suite
  ;; runs from the source tree, so that is what this must report — and it must
  ;; be absolute, since the client displays it verbatim from another directory.
  (let [source (:source (json-body (call (req :get "/health"))))]
    (is (str/ends-with? source " (source)"))
    (is (str/starts-with? source "/"))))

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

(deftest roots-lists-the-indexed-trees
  (testing "GET /roots reports each tree with the recursive flag it was indexed with"
    (with-redefs [roots/read-roots (constantly [{:path "/notes" :recursive true}
                                                {:path "/a.md" :recursive false}])]
      (let [resp (call (req :get "/roots"))]
        (is (= 200 (:status resp)))
        (is (= {:roots [{:path "/notes" :recursive true}
                        {:path "/a.md" :recursive false}]}
               (json-body resp)))))))

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
      (let [resp (call (req :post "/index" {:paths [(the-file)]}))
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

(deftest reindex-streams-ndjson-and-leaves-roots-alone
  (testing "the reindex route streams like /index but records nothing: a rebuild of
            what is already indexed is not a request to start tracking anything"
    (with-redefs [index/reindex! (fn [_cancelled? emit]
                                   (emit {:type "start" :total 2})
                                   {:indexed 2 :chunks 7 :skipped 0 :unchanged 0 :errors []})
                  ;; the guard: /index calls this, /reindex must not
                  roots/add-roots! (fn [& _] (throw (AssertionError. "reindex touched roots.edn")))]
      (let [resp (call (req :post "/reindex"))
            out  (java.io.ByteArrayOutputStream.)]
        (is (= 200 (:status resp)))
        (is (= "application/x-ndjson" (get-in resp [:headers "Content-Type"])))
        (rcp/write-body-to-stream (:body resp) resp out)
        (let [events (mapv #(j/read-value (str/trim %) mapper)
                           (remove str/blank? (str/split-lines (str out))))]
          (is (= "start" (:type (first events))))
          (is (= {:type "result" :result {:indexed 2 :chunks 7 :skipped 0
                                          :unchanged 0 :errors []}}
                 (last events)))))
      ;; the fixture's roots file starts empty; nothing may have been written to it
      (is (zero? (.length config/roots-file))))))

(deftest reindex-takes-no-paths
  (testing "the file list is the server's own, so a body naming paths is ignored"
    (with-redefs [index/reindex! (fn [_ _] {:indexed 0 :chunks 0 :skipped 0
                                            :unchanged 0 :errors []})]
      (is (= 200 (:status (call (req :post "/reindex" {:paths ["/nope.md"]}))))))))

(deftest query-dimension-mismatch-is-400-with-remediation
  (testing "a dimension-mismatch ex-info from search maps to 400, message + data intact"
    (with-redefs [index/search (fn [_]
                                 (throw (ex-info "dim changed\n  otzar admin clear\n  otzar index <paths…>"
                                                 {:type :dimension-mismatch :indexed 1024 :configured 768})))]
      (let [resp (call (req :post "/query" {:q "x" :mode "vector"}))
            body (json-body resp)]
        (is (= 400 (:status resp)))
        (is (str/includes? (:error body) "otzar admin clear"))
        (is (= "dimension-mismatch" (get-in body [:data :type])))))))

(deftest index-dimension-mismatch-is-a-stream-error-event
  (testing "a mismatch during indexing arrives as the terminal NDJSON error event"
    (with-redefs [index/index-paths!
                  (fn [& _] (throw (ex-info "dim changed\n  otzar admin clear" {:type :dimension-mismatch})))]
      (let [resp (call (req :post "/index" {:paths [(the-file)]}))
            out  (java.io.ByteArrayOutputStream.)]
        (rcp/write-body-to-stream (:body resp) resp out)
        (let [events (->> (str/split-lines (str out))
                          (remove str/blank?)
                          (mapv #(j/read-value (str/trim %) mapper)))]
          (is (= "error" (:type (last events))))
          (is (str/includes? (:error (last events)) "otzar admin clear")))))))

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

;; ── root validation on POST /index ──────────────────────────────────────────
;; A path posted to /index is persisted as a root before the walk begins, so an
;; unusable one is a lasting mistake: saved forever, indexing nothing, and — since
;; a root missing from disk reads the same as an unmounted volume — blocking the
;; watch sweep's pruning from then on.

(deftest index-refuses-a-path-that-is-not-on-disk
  (with-redefs [index/index-paths! (fn [& _] (throw (AssertionError. "should not index")))
                roots/add-roots!   (fn [& _] (throw (AssertionError. "should not record a root")))]
    (let [resp (call (req :post "/index" {:paths ["/no/such/place"]}))
          body (json-body resp)]
      (is (= 400 (:status resp)))
      (is (str/includes? (:error body) "/no/such/place"))
      (is (str/includes? (:error body) "no such file or directory"))
      (is (= "unusable-paths" (get-in body [:data :type]))))))

(deftest index-refuses-the-whole-request-when-one-path-is-bad
  (testing "half-indexing and recording the good path would hide the typo"
    (with-redefs [index/index-paths! (fn [& _] (throw (AssertionError. "should not index")))
                  roots/add-roots!   (fn [& _] (throw (AssertionError. "should not record a root")))]
      (let [resp (call (req :post "/index" {:paths [(the-file) "/no/such/place"]}))]
        (is (= 400 (:status resp)))
        (is (= ["/no/such/place"] (mapv :path (get-in (json-body resp) [:data :paths]))))))))

(deftest index-accepts-a-path-that-exists
  (with-redefs [index/index-paths! (fn [_ _ _ _] {:indexed 1 :chunks 1 :skipped 0
                                                  :unchanged 0 :errors []})]
    (is (= 200 (:status (call (req :post "/index" {:paths [(the-file)]})))))
    (testing "and records it as a root"
      (is (= [(the-file)] (mapv :path (roots/read-roots)))))))
