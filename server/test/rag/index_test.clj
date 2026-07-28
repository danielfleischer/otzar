(ns rag.index-test
  "Tests for the index facade's orchestration logic: filter normalisation, mtime
  coercion, search-mode dispatch, and the change-detection gate in file->chunks.
  Lucene, the extractor/segmenter/chunker, and the embedder are all stubbed, so
  nothing touches disk or Ollama — except the prune! section at the end, which
  reconciles a real (throwaway) index against real temp files, since that
  reconciliation is precisely what a stub would hide."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [rag.index :as index]
            [rag.index.lucene :as lucene]
            [rag.index.extractor :as extractor]
            [rag.index.segmenter :as segmenter]
            [rag.index.chunker :as chunker]
            [rag.roots :as roots]
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
          (is (re-find #"otzar index" (.getMessage ex))))))
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

;; ── sweep! orchestration ────────────────────────────────────────────────────
;; prune! and index-paths! are stubbed, so nothing touches Lucene/Ollama; only the
;; root filtering, the all-present prune gate, and the recursive grouping are under
;; test. A real temp dir stands in for a "present" root (sweep! stats it).

(defn- temp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir") (str "sweep-test-" (System/nanoTime)))
    (.mkdirs)))

(deftest sweep-reindexes-present-roots-and-prunes-when-all-present
  (let [dir (temp-dir)]
    (try
      (let [calls (atom [])]
        (with-redefs [roots/read-roots   (constantly [{:path (.getPath dir) :recursive true}])
                      index/prune!       (fn [_] {:removed ["/gone.txt"]})
                      index/index-paths! (fn [paths opts]
                                           (swap! calls conj [paths opts])
                                           {:indexed 1 :chunks 2 :unchanged 3 :skipped 0 :errors []})]
          (let [r (index/sweep!)]
            (is (= 1 (:roots r)))
            (is (= 1 (:present r)))
            (is (= ["/gone.txt"] (:pruned r)))
            (is (= 1 (:indexed r)))
            (is (= 3 (:unchanged r)))
            (testing "the present root is re-indexed with its recursive flag"
              (is (= [[[(.getPath dir)] {:recursive true}]] @calls))))))
      (finally (.delete dir)))))

(deftest sweep-skips-prune-when-a-root-is-missing
  (let [dir (temp-dir)]
    (try
      (with-redefs [roots/read-roots   (constantly [{:path (.getPath dir) :recursive true}
                                                    {:path "/definitely/not/here" :recursive true}])
                    index/prune!       (fn [_] (throw (AssertionError. "must not prune with a missing root")))
                    index/index-paths! (fn [_ _] {:indexed 0 :chunks 0 :unchanged 1 :skipped 0 :errors []})]
        (let [r (index/sweep!)]
          (testing "the vanished root is counted but not present, and pruning is skipped"
            (is (= 2 (:roots r)))
            (is (= 1 (:present r)))
            (is (= [] (:pruned r))))
          (testing "present roots are still re-indexed"
            (is (= 1 (:unchanged r))))))
      (finally (.delete dir)))))

(deftest sweep-with-no-roots-is-a-noop
  (with-redefs [roots/read-roots   (constantly [])
                index/prune!       (fn [_] (throw (AssertionError. "must not prune with no roots")))
                index/index-paths! (fn [_ _] (throw (AssertionError. "must not index with no roots")))]
    (let [r (index/sweep!)]
      (is (= 0 (:roots r)))
      (is (= 0 (:indexed r))))))

(deftest sweep-groups-recursive-and-non-recursive-roots
  (let [rdir (temp-dir)
        fdir (temp-dir)]
    (try
      (let [calls (atom [])]
        (with-redefs [roots/read-roots   (constantly [{:path (.getPath rdir) :recursive true}
                                                      {:path (.getPath fdir) :recursive false}])
                      index/prune!       (fn [_] {:removed []})
                      index/index-paths! (fn [paths opts]
                                           (swap! calls conj [(set paths) opts])
                                           {:indexed 0 :chunks 0 :unchanged 0 :skipped 0 :errors []})]
          (index/sweep!)
          (testing "each recursive-flag group is one index-paths! call carrying that flag"
            (is (= #{[#{(.getPath rdir)} {:recursive true}]
                     [#{(.getPath fdir)} {:recursive false}]}
                   (set @calls))))))
      (finally (.delete rdir) (.delete fdir)))))

;; ── prune! ──────────────────────────────────────────────────────────────────
;; Real temp files and a real throwaway Lucene index (no embedding — chunks are
;; written straight in). prune! decides what to drop by comparing the index
;; against disk, so both sides have to be real for the test to mean anything.
;; The invariant these guard, beyond the counts: prune! reconciles the *index*
;; and must never touch a file on disk.

(defn- rm-r [dir]
  (run! io/delete-file (reverse (file-seq dir))))

(defn- snapshot
  "Every file under `dir` as absolute-path -> [length mtime sha1-of-contents], so
  a test can prove a tree came through an index run or a prune untouched: no file
  created, removed, rewritten, or even re-stamped."
  [dir]
  (into {} (for [^File f (file-seq dir) :when (.isFile f)]
             [(.getAbsolutePath f) [(.length f) (.lastModified f) (#'index/sha1 (slurp f))]])))

(defn- chunk-for
  "One chunk map standing in for `file`'s indexed content, keyed the way
  index-paths! would key it (doc_id = sha1 of the absolute path)."
  [^File file]
  (let [path   (.getAbsolutePath file)
        doc-id (#'index/sha1 path)]
    {:id (str doc-id ":0") :doc_id doc-id :path path :ext "md"
     :mtime (.lastModified file) :chunk_idx 0 :text (str "contents of " path)}))

(defn- with-indexed-files
  "Create `n` real .md files in a temp dir, index one chunk each into a throwaway
  Lucene index, and call `(f files idx)` with index/ensure! pointed at it. Both
  temp dirs are removed afterwards."
  [n f]
  (let [dir     (temp-dir)
        idx-dir (temp-dir)
        files   (mapv (fn [i]
                        (doto (io/file dir (str "f" i ".md")) (spit "hello")))
                      (range n))
        idx     (lucene/open idx-dir)]
    (try
      (lucene/add-chunks! idx (map chunk-for files))
      (lucene/commit! idx)
      (with-redefs [index/ensure! (constantly idx)]
        (f files idx))
      (finally
        (lucene/close idx)
        (rm-r dir)
        (rm-r idx-dir)))))

;; 20 files, one deleted, is deliberate: at 5% of the segment the deletion is
;; below TieredMergePolicy's *natural* deletesPctAllowed (20%), so no background
;; merge will expunge it on its own and prune!'s own reclaim is the only thing
;; that can — which is what we want to test, and what keeps the assertions
;; deterministic (a natural merge would land asynchronously, after the commit).
(deftest prune-drops-only-vanished-files
  (with-indexed-files 20
    (fn [files idx]
      (let [[gone & kept] files
            gone-path (.getAbsolutePath ^File gone)
            dir       (.getParentFile ^File gone)]
        (.delete ^File gone)
        (let [before (snapshot dir)
              r      (index/prune!)]
          (is (= [gone-path] (:removed r)))
          (is (= 19 (:kept r)))
          (is (nil? (:dry_run r)))

          (testing "prune reconciles the index; the directory on disk is untouched"
            (is (= before (snapshot dir)))))

        (testing "the files that still exist are untouched on disk"
          (is (every? #(.exists ^File %) kept)))

        (testing "only the vanished file's chunks left the index"
          (is (= (sort (map #(.getAbsolutePath ^File %) kept)) (lucene/paths idx)))
          (is (= {:num_docs 19 :num_chunks 19} (lucene/stats idx))))

        (testing "and it stays gone — a second prune finds nothing to remove"
          ;; The watch-sweep bug: a prune that doesn't stick reports the same
          ;; orphan on every sweep, forever.
          (is (= {:removed [] :kept 19} (index/prune!))))))))

(deftest prune-with-nothing-missing-changes-nothing
  (with-indexed-files 3
    (fn [files idx]
      (let [paths (sort (map #(.getAbsolutePath ^File %) files))]
        (is (= {:removed [] :kept 3} (index/prune!)))
        (testing "every file is still on disk and still indexed"
          (is (every? #(.exists ^File %) files))
          (is (= paths (lucene/paths idx)))
          (is (= {:num_docs 3 :num_chunks 3} (lucene/stats idx))))))))

(deftest prune-dry-run-reports-without-removing
  (with-indexed-files 2
    (fn [files idx]
      (let [[gone keep] files
            gone-path (.getAbsolutePath ^File gone)]
        (.delete ^File gone)
        (let [r (index/prune! {:dry-run true})]
          (is (= [gone-path] (:removed r)))
          (is (= 1 (:kept r)))
          (is (true? (:dry_run r))))

        (testing "the index is left as it was — the orphan is still listed"
          (is (= [gone-path (.getAbsolutePath ^File keep)] (lucene/paths idx)))
          (is (= {:num_docs 2 :num_chunks 2} (lucene/stats idx))))

        (testing "and the file that does exist is still there"
          (is (.exists ^File keep)))))))

(deftest prune-on-an-empty-index-is-a-noop
  (with-indexed-files 0
    (fn [_files _idx]
      (is (= {:removed [] :kept 0} (index/prune!))))))

;; ── the source tree is read-only ────────────────────────────────────────────
;; otzar reads what it indexes and writes only its own state under ~/.otzar.
;; These run the real walk → extract → segment → chunk pipeline over a real tree
;; (only the embedder and the index location are stubbed) and prove the tree
;; comes back byte-for-byte, mtimes included. rag.fs-safety-test guards the same
;; property at the source level, for code that doesn't exist yet.

(defn- source-tree
  "A small tree in a fresh temp dir: two indexable files (one nested) and one the
  walker skips for its extension."
  []
  (let [dir (temp-dir)
        sub (doto (io/file dir "notes") (.mkdirs))]
    (spit (io/file dir "a.md") "Alpha beta gamma.\nA second line about indexing.\n")
    (spit (io/file sub "b.txt") "Nested plain text, also indexable.\n")
    (spit (io/file dir "skipped.bin") "no extractor claims this extension\n")
    dir))

(defn- with-real-pipeline
  "Call `(f dir idx)` with a real source tree and a real throwaway index, with
  only the Ollama-bound calls stubbed (fixed 3-dim vectors) and index/ensure!
  pointed at the temp index. Cleans up both temp dirs."
  [f]
  (let [dir     (source-tree)
        idx-dir (temp-dir)
        idx     (lucene/open idx-dir)]
    (try
      (with-redefs [index/ensure!         (constantly idx)
                    embed/embedding-dim   (constantly 3)
                    embed/model-spec      (constantly spec)
                    embed/embed-documents (fn [texts & _]
                                            (mapv (fn [_] (float-array [0.1 0.2 0.3])) texts))]
        (f dir idx))
      (finally
        (lucene/close idx)
        (rm-r dir)
        (rm-r idx-dir)))))

(deftest indexing-never-writes-to-the-source-tree
  (with-real-pipeline
    (fn [dir idx]
      (let [before (snapshot dir)
            r      (index/index-paths! [(.getPath dir)] {:recursive true})]
        (testing "the two indexable files are indexed, the third never opened"
          (is (= 2 (:indexed r)))
          (is (empty? (:errors r)))
          (is (= 2 (:num_docs (lucene/stats idx)))))

        (testing "no file was created, removed, rewritten, or re-stamped"
          (is (= before (snapshot dir))))

        (testing "and neither does a re-index, or a forced re-embed of every file"
          (index/index-paths! [(.getPath dir)] {:recursive true})
          (index/index-paths! [(.getPath dir)] {:recursive true :force true})
          (is (= before (snapshot dir))))))))

(deftest clearing-the-index-leaves-the-source-tree-alone
  ;; `otzar admin clear` wipes the index — never what was indexed.
  (with-real-pipeline
    (fn [dir idx]
      (index/index-paths! [(.getPath dir)] {:recursive true})
      (let [before (snapshot dir)]
        (is (= {:removed_docs 2 :removed_chunks 2} (index/clear!)))
        (is (= {:num_docs 0 :num_chunks 0} (lucene/stats idx)))
        (is (= before (snapshot dir)))))))
