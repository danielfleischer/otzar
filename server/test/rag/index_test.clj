(ns rag.index-test
  "Tests for the index facade's orchestration logic: filter normalisation, mtime
  coercion, search-mode dispatch, and the change-detection gate in file->chunks.
  Lucene, the extractor/segmenter/chunker, and the embedder are all stubbed, so
  nothing touches disk or Ollama."
  (:require [clojure.test :refer [deftest is testing]]
            [rag.index :as index]
            [rag.index.lucene :as lucene]
            [rag.index.extractor :as extractor]
            [rag.index.segmenter :as segmenter]
            [rag.index.chunker :as chunker]
            [rag.embed :as embed]
            [rag.query.hybrid :as hybrid])
  (:import [java.io File]))

;; ── ->filter-spec ───────────────────────────────────────────────────────────

(deftest filter-spec-normalisation
  (let [->spec #'index/->filter-spec]
    (testing "no usable constraint -> nil"
      (is (nil? (->spec nil)))
      (is (nil? (->spec {}))))
    (testing "a scalar ext is wrapped into a vector of strings"
      (is (= {:ext ["md"]} (->spec {:ext "md"}))))
    (testing "a collection of exts is kept, coerced to strings"
      (is (= {:ext ["md" "txt"]} (->spec {:ext ["md" "txt"]}))))
    (testing "path_prefix passes through as a string"
      (is (= {:path_prefix "/notes/"} (->spec {:path_prefix "/notes/"}))))
    (testing "mtime_after is coerced to epoch millis"
      (is (= {:mtime_after 1735689600000} (->spec {:mtime_after "2025-01-01"}))))
    (testing "multiple constraints combine"
      (is (= {:ext ["md"] :path_prefix "/n/"}
             (->spec {:ext "md" :path_prefix "/n/"}))))))

;; ── ->epoch-ms ──────────────────────────────────────────────────────────────

(deftest epoch-ms-coercion
  (let [->ms #'index/->epoch-ms]
    (testing "a number is already millis"
      (is (= 123 (->ms 123))))
    (testing "a full ISO-8601 instant"
      (is (= 1735689600000 (->ms "2025-01-01T00:00:00Z"))))
    (testing "a bare date reads as start-of-day UTC"
      (is (= 1735689600000 (->ms "2025-01-01"))))
    (testing "a non-string, non-number is a bad-filter ex-info"
      (let [ex (is (thrown? clojure.lang.ExceptionInfo (->ms :nope)))]
        (is (= :bad-filter (:type (ex-data ex))))))))

;; ── search mode dispatch ────────────────────────────────────────────────────

(deftest search-dispatches-by-mode
  (with-redefs [index/ensure!        (constantly :comp)
                lucene/vector-dim    (constantly nil)   ; empty index: dim gate is a no-op
                lucene/filter-query  (constantly :filt)
                lucene/keyword-search (fn [_ _ _ _] [{:id "kw"}])
                lucene/vector-search  (fn [_ _ _ _] [{:id "vec"}])
                embed/embed-query     (fn [_] (float-array [1.0]))
                hybrid/fuse           (fn [_ _ _] [{:id "fused"}])]
    (testing "keyword mode uses BM25 only"
      (is (= [{:id "kw"}] (:hits (index/search {:q "x" :mode "keyword"})))))
    (testing "vector mode embeds the query and runs kNN"
      (is (= [{:id "vec"}] (:hits (index/search {:q "x" :mode "vector"})))))
    (testing "hybrid mode fuses both retrievers"
      (is (= [{:id "fused"}] (:hits (index/search {:q "x" :mode "hybrid"})))))
    (testing "an unknown mode is rejected"
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (index/search {:q "x" :mode "bogus"})))]
        (is (= :unsupported-mode (:type (ex-data ex))))))))

;; ── dimension-mismatch gate ─────────────────────────────────────────────────

(deftest ensure-dim-detects-a-changed-dimension
  (let [ensure-dim! #'index/ensure-dim!]
    (testing "index holds a different dimension than the model now produces -> throws"
      (with-redefs [lucene/vector-dim (constantly 1024)]
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (ensure-dim! :comp 768)))]
          (is (= :dimension-mismatch (:type (ex-data ex))))
          (is (= {:indexed 1024 :configured 768}
                 (select-keys (ex-data ex) [:indexed :configured])))
          ;; the message must tell the user exactly what to run
          (is (re-find #"admin clear" (.getMessage ex)))
          (is (re-find #"otzer index" (.getMessage ex))))))
    (testing "matching dimension -> no-op"
      (with-redefs [lucene/vector-dim (constantly 1024)]
        (is (nil? (ensure-dim! :comp 1024)))))
    (testing "empty/keyword-only index (no stored vectors) -> no-op even on a change"
      (with-redefs [lucene/vector-dim (constantly nil)]
        (is (nil? (ensure-dim! :comp 768)))))
    (testing "unknown model dimension (Ollama silent) -> no-op, defer to the write"
      (with-redefs [lucene/vector-dim (fn [_] (throw (AssertionError. "should not check")))]
        (is (nil? (ensure-dim! :comp nil)))))))

(deftest search-fails-fast-on-dimension-change
  (with-redefs [index/ensure!         (constantly :comp)
                lucene/vector-dim     (constantly 1024)
                lucene/filter-query   (constantly nil)
                lucene/keyword-search (fn [_ _ _ _] [{:id "kw"}])
                lucene/vector-search  (fn [& _] (throw (AssertionError. "must not reach kNN")))
                embed/embed-query     (fn [_] (float-array (repeat 768 (float 0))))]
    (testing "a vector query at the wrong dimension throws before kNN"
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (index/search {:q "x" :mode "vector"})))]
        (is (= :dimension-mismatch (:type (ex-data ex))))))
    (testing "a keyword query never embeds, so it is unaffected by the dim gate"
      (is (= [{:id "kw"}] (:hits (index/search {:q "x" :mode "keyword"})))))))

(deftest index-paths-fails-fast-on-dimension-change
  (testing "the run is refused up front, before any file is walked or embedded"
    (with-redefs [index/ensure!       (constantly :comp)
                  lucene/vector-dim   (constantly 1024)
                  embed/embedding-dim (constantly 768)
                  embed/model-spec    (fn [] (throw (AssertionError. "must not get past the gate")))]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (index/index-paths! ["/some/path"] {})))]
        (is (= :dimension-mismatch (:type (ex-data ex))))))))

;; ── file->chunks change detection ───────────────────────────────────────────

(def ^:private spec "bge-m3@abc123/1024d")
(def ^:private text "hello world")

(defn- with-file [f]
  (let [file (File/createTempFile "index-test" ".md")]
    (spit file text)
    (.setLastModified file 1600000000000)
    (try (f file (.lastModified file))
         (finally (.delete file)))))

;; The extract/segment/chunk/embed pipeline is fixed for every case; only
;; lucene/doc-meta (the prior indexed state) and the force flag vary.
(defn- run-file->chunks [prior file force?]
  (with-redefs [lucene/doc-meta        (constantly prior)
                extractor/extract       (constantly text)
                segmenter/segment       (constantly [{:text text :loc 1}])
                chunker/pack            (constantly [{:chunk_idx 0 :text text
                                                      :loc_start 1 :loc_end 1}])
                embed/embed-documents   (fn [texts & _] (mapv (fn [_] (float-array [0.5])) texts))]
    (#'index/file->chunks :comp spec file (constantly false) (fn [_]) force?)))

(deftest file->chunks-detects-changes
  (with-file
    (fn [file mtime]
      (testing "no prior index entry -> embed (a fresh file)"
        (let [r (run-file->chunks nil file false)]
          (is (vector? r))
          (is (= 1 (count r)))
          (is (= spec (:model_spec (first r))))))

      (testing "same spec + same mtime -> unchanged, without even reading the file"
        (with-redefs [extractor/extract (fn [_] (throw (AssertionError. "should not extract")))]
          (with-redefs [lucene/doc-meta (constantly {:model_spec spec :mtime mtime})]
            (is (= :rag.index/unchanged
                   (#'index/file->chunks :comp spec file (constantly false) (fn [_]) false))))))

      (testing "mtime bumped but content identical -> unchanged (content hash matches)"
        (let [csha (#'index/sha1 text)
              prior {:model_spec spec :mtime (dec mtime) :content_sha1 csha}]
          (is (= :rag.index/unchanged (run-file->chunks prior file false)))))

      (testing "mtime bumped and content differs -> re-embed"
        (let [prior {:model_spec spec :mtime (dec mtime) :content_sha1 "stale-hash"}]
          (is (= 1 (count (run-file->chunks prior file false))))))

      (testing "a different model spec forces a re-embed even when bytes are untouched"
        (let [prior {:model_spec "old-model@x/768d" :mtime mtime}]
          (is (= 1 (count (run-file->chunks prior file false))))))

      (testing "force? re-embeds regardless of an otherwise-unchanged file"
        (let [prior {:model_spec spec :mtime mtime}]
          (is (= 1 (count (run-file->chunks prior file true)))))))))

(deftest file->chunks-skips-unsupported-files
  (with-file
    (fn [file _mtime]
      (testing "an extractor that returns nil (unsupported type) yields nil"
        (with-redefs [lucene/doc-meta   (constantly nil)
                      extractor/extract (constantly nil)]
          (is (nil? (#'index/file->chunks :comp spec file (constantly false) (fn [_]) false))))))))

(deftest file->chunks-stamps-the-format-s-locator
  (testing "the chunk records which unit its span counts, from the file's format"
    ;; The registry decides (a .md is lines, a .pdf is pages) and the segmenter is
    ;; told the same thing — so a hit can say `file.md:42-47` vs `file.pdf p.4`
    ;; without the client guessing from the extension.
    (with-file
      (fn [file _mtime]
        (is (= "line" (:locator (first (run-file->chunks nil file false)))))))
    (let [pdf (File/createTempFile "index-test" ".pdf")]
      (try
        (spit pdf text)
        (is (= "page" (:locator (first (run-file->chunks nil pdf false)))))
        (finally (.delete pdf))))))
