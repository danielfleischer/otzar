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

;; ── reindex! ────────────────────────────────────────────────────────────────
;; index-paths! is stubbed; what's under test is where reindex! gets its file list
;; and which options it forces.

(deftest reindex-forces-a-rebuild-of-the-indexed-files
  (let [calls (atom [])]
    (with-redefs [index/paths        (constantly {:count 2 :paths ["/a.md" "/b.org"]})
                  roots/read-roots   (fn [] (throw (AssertionError. "reindex must not read roots")))
                  index/index-paths! (fn [paths opts _cancelled? _on-progress]
                                       (swap! calls conj [paths opts])
                                       {:indexed 2 :chunks 9 :unchanged 0 :skipped 0 :errors []})]
      (let [r (index/reindex! (constantly false) (fn [_]))]
        (is (= 2 (:indexed r)))
        (testing "the index's own files, forced, and flat — they're concrete paths"
          (is (= [[["/a.md" "/b.org"] {:recursive false :force true}]] @calls)))))))

(deftest reindex-of-an-empty-index-does-nothing
  (with-redefs [index/paths        (constantly {:count 0 :paths []})
                index/index-paths! (fn [& _] (throw (AssertionError. "nothing to reindex")))]
    (is (= {:indexed 0 :chunks 0 :skipped 0 :unchanged 0 :removed 0 :errors []}
           (index/reindex! (constantly false) (fn [_]))))))

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

;; ── a multi-file prune actually sticks ──────────────────────────────────────

(deftest prune-sticks-when-more-than-one-file-is-gone
  ;; The single-file prune above passed for two months while this did not:
  ;; delete-by-paths! buffers its deletes, and forceMergeDeletes skipped a segment
  ;; whose deletes were still buffered, expunging nothing. The chunks went, but
  ;; every path stayed in the term dictionary — so `otzar list` kept naming files
  ;; that were no longer indexed and the next sweep pruned them all over again.
  (with-indexed-files 20
    (fn [files idx]
      (let [[gone kept] (split-at 5 files)
            gone-paths  (sort (map #(.getAbsolutePath ^File %) gone))]
        (run! #(.delete ^File %) gone)
        (is (= gone-paths (:removed (index/prune!))))

        (testing "the term dictionary agrees with what is actually indexed"
          (is (= (sort (map #(.getAbsolutePath ^File %) kept)) (lucene/paths idx)))
          (is (= {:num_docs 15 :num_chunks 15} (lucene/stats idx))))

        (testing "and they stay gone — a second prune finds nothing"
          (is (= {:removed [] :kept 15} (index/prune!))))))))

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

;; ── stale chunks for a file that stops being extractable ────────────────────
;; The bug these guard: a file counted as "skipped" left its previously indexed
;; chunks in place, so text that no longer exists anywhere on disk stayed
;; searchable — and `otzar list` kept naming the file as indexed.

(defn- with-bigger-tree
  "Call `(f dir idx)` over a tree of `n` indexable .md files and a real throwaway
  index, embedder stubbed as in with-real-pipeline.

  `n` is 6 rather than 2 for the same reason the prune tests use 20: reclaiming a
  deleted doc's terms depends on the merge policy actually rewriting the segment,
  and TieredMergePolicy declines to rewrite a lone segment of four docs or fewer
  whatever forceMergeDeletesPctAllowed says. Below that floor the chunk is deleted
  and unsearchable but its path lingers in the term dictionary, so /paths and
  :num_docs would read stale — a pre-existing limitation of reclaim-deletes! that
  `otzar admin prune` shares, not something these tests are about."
  [n f]
  (let [dir     (temp-dir)
        idx-dir (temp-dir)]
    (dotimes [i n]
      (spit (io/file dir (str "f" i ".md")) (str "Alpha beta gamma number " i ".\n")))
    (let [idx (lucene/open idx-dir)]
      (try
        (with-redefs [index/ensure!         (constantly idx)
                      embed/embedding-dim   (constantly 3)
                      embed/model-spec      (constantly spec)
                      embed/embed-documents (fn [texts & _]
                                              (mapv (fn [_] (float-array [0.1 0.2 0.3])) texts))]
          (f dir idx))
        (finally (lucene/close idx) (rm-r dir) (rm-r idx-dir))))))

(defn- becomes-unextractable!
  "Make an already-indexed file stop yielding text, the way a real one does when it
  is emptied or overwritten with something binary, and bump its mtime so the change
  gate notices."
  [^File f kind]
  (case kind
    :emptied (spit f "   \n\n  \n")
    :binary  (with-open [os (io/output-stream f)]
               (.write os (byte-array (map byte [0 1 2 65 66])))))
  (.setLastModified f (+ 100000 (.lastModified f))))

(deftest a-file-that-stops-being-extractable-loses-its-chunks
  (doseq [kind [:emptied :binary]]
    (testing (str "a previously indexed file, now " (name kind))
      (with-bigger-tree 6
        (fn [dir idx]
          (index/index-paths! [(.getPath dir)] {:recursive true})
          (is (= 6 (:num_docs (lucene/stats idx))))

          (let [f (io/file dir "f0.md")
                p (.getAbsolutePath f)]
            (becomes-unextractable! f kind)
            (let [r (index/index-paths! [(.getPath dir)] {:recursive true})]
              (testing "it is reported as removed, not silently skipped"
                (is (= 1 (:removed r)))
                (is (zero? (:skipped r))))
              (testing "its chunks are gone — nothing of it is searchable or listed"
                (is (= 5 (:num_docs (lucene/stats idx))))
                (is (= 5 (:num_chunks (lucene/stats idx))))
                (is (not (some #{p} (lucene/paths idx)))))
              (testing "the other files are untouched"
                (is (= 5 (count (lucene/paths idx)))))
              (testing "and the file itself is still on disk — only the index changed"
                (is (.exists f))))))))))

(deftest a-file-never-indexed-is-skipped-not-removed
  (with-bigger-tree 6
    (fn [dir idx]
      (index/index-paths! [(.getPath dir)] {:recursive true})
      ;; A new file that never indexes: there is nothing stale to drop, so it is
      ;; still a plain skip and the counts must say so.
      (let [f (io/file dir "junk.md")]
        (with-open [os (io/output-stream f)]
          (.write os (byte-array (map byte [0 1 2 65]))))
        (let [r (index/index-paths! [(.getPath dir)] {:recursive true})]
          (is (= 1 (:skipped r)))
          (is (zero? (:removed r)))
          (is (= 6 (:num_docs (lucene/stats idx)))))))))

(deftest a-vanished-file-is-left-for-prune
  ;; Deletion is prune!'s job, and deliberately so: it is the one with the
  ;; mass-deletion guard for an unmounted volume. An index run must not take that
  ;; decision on its own.
  (with-bigger-tree 6
    (fn [dir idx]
      (index/index-paths! [(.getPath dir)] {:recursive true})
      (let [f (io/file dir "f0.md")
            p (.getAbsolutePath f)]
        (.delete f)
        (let [r (index/reindex! (constantly false) (fn [_]))]
          (is (zero? (:removed r)))
          (is (some #{p} (lucene/paths idx))))))))

(deftest an-ordinary-run-removes-nothing
  (with-real-pipeline
    (fn [dir idx]
      (let [r (index/index-paths! [(.getPath dir)] {:recursive true})]
        (is (= 2 (:indexed r)))
        (is (zero? (:removed r)))
        (is (= 2 (:num_docs (lucene/stats idx)))))
      (testing "nor does a second run over an unchanged tree"
        (let [r (index/index-paths! [(.getPath dir)] {:recursive true})]
          (is (= 2 (:unchanged r)))
          (is (zero? (:removed r))))))))

;; ── the mutation lock ───────────────────────────────────────────────────────
;; Before this, clear and prune ran straight through an index in flight. A clear
;; mid-run was the worst of them: it wiped, reported the counts it had removed, and
;; then the run's own commits put every file straight back.

(defn- during-an-index-run
  "Start an index run that blocks inside the embedder, call `(f)` while it is in
  flight, then let the run finish. Returns [f's value-or-thrown, the run's result]."
  [dir f]
  (let [gate    (promise)
        started (promise)]
    (with-redefs [embed/embed-documents (fn [texts & _]
                                          (deliver started true)
                                          @gate
                                          (mapv (fn [_] (float-array [0.1 0.2 0.3])) texts))]
      (let [run (future (index/index-paths! [(.getPath dir)] {:recursive true}))]
        @started
        (let [outcome (try {:value (f)} (catch Exception e {:thrown e}))]
          (deliver gate true)
          [outcome @run])))))

(deftest clear-is-refused-while-an-index-run-holds-the-lock
  (with-real-pipeline
    (fn [dir idx]
      (let [[outcome run] (during-an-index-run dir #(index/clear!))]
        (testing "the clear is refused as busy rather than silently losing the race"
          (is (nil? (:value outcome)))
          (is (= :index-busy (:type (ex-data (:thrown outcome))))))
        (testing "and the index run completes intact"
          (is (= 2 (:indexed run)))
          (is (= 2 (:num_docs (lucene/stats idx)))))))))

(deftest prune-is-refused-while-an-index-run-holds-the-lock
  (with-real-pipeline
    (fn [dir _idx]
      (let [[outcome _] (during-an-index-run dir #(index/prune!))]
        (is (= :index-busy (:type (ex-data (:thrown outcome)))))))))

(deftest a-dry-run-prune-still-answers-mid-index
  ;; It mutates nothing, so refusing it would only make the preview useless exactly
  ;; when someone wants it.
  (with-real-pipeline
    (fn [dir _idx]
      (let [[outcome _] (during-an-index-run dir #(index/prune! {:dry-run true}))]
        (is (nil? (:thrown outcome)))
        (is (true? (:dry_run (:value outcome))))))))

(deftest clear-and-prune-work-normally-when-nothing-is-running
  (with-real-pipeline
    (fn [dir idx]
      (index/index-paths! [(.getPath dir)] {:recursive true})
      (is (= {:removed [] :kept 2} (index/prune!)))
      (is (= {:removed_docs 2 :removed_chunks 2} (index/clear!)))
      (is (= {:num_docs 0 :num_chunks 0} (lucene/stats idx)))
      (testing "the lock is released, so the next mutation still runs"
        (is (= 2 (:indexed (index/index-paths! [(.getPath dir)] {:recursive true}))))))))

(deftest the-sweep-still-skips-a-tick-rather-than-throwing
  ;; The sweep shares the lock but must never surface :index-busy — a background
  ;; tick that collides with a manual index just does nothing.
  (with-real-pipeline
    (fn [dir _idx]
      (let [[outcome _] (during-an-index-run dir #(index/sweep!))]
        (is (nil? (:thrown outcome)))
        (is (= {:skipped true} (:value outcome)))))))
