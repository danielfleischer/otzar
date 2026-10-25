(ns rag.index.lucene-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [rag.index.lucene :as lucene])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.apache.lucene.document Document Field$Store StoredField StringField]
           [org.apache.lucene.index IndexWriter]))

(def ^:dynamic *idx* nil)

(defn- tmp-index-fixture
  "Open a Lucene index in a throwaway directory for one test, then close it and
  delete the directory. Isolated from ~/.otzar — nothing here touches the real
  index."
  [f]
  (let [dir (.toFile (Files/createTempDirectory "lucene-test" (make-array FileAttribute 0)))
        idx (lucene/open dir)]
    (binding [*idx* idx]
      (try (f)
           (finally
             (lucene/close idx)
             (run! io/delete-file (reverse (file-seq dir))))))))

(use-fixtures :each tmp-index-fixture)

(defn- mk-chunk
  "Minimal chunk map (the shape add-chunks! expects). No :embedding — clear! and
  the counts don't depend on vectors."
  [doc-id path idx]
  {:id (str doc-id ":" idx) :doc_id doc-id :path path
   :mtime 0 :chunk_idx idx :text (str "chunk " idx " of " path)})

(defn- seed!
  "Add three chunks across two source files and commit, so stats/paths read them."
  []
  (lucene/add-chunks! *idx* [(mk-chunk "d1" "/a.md" 0)
                             (mk-chunk "d1" "/a.md" 1)
                             (mk-chunk "d2" "/b.md" 0)])
  (lucene/commit! *idx*))

(deftest clear-empties-the-index
  (testing "clear! drops every chunk of every file; stats and paths read empty"
    (seed!)
    (is (= {:num_docs 2 :num_chunks 3} (lucene/stats *idx*)))
    (is (= ["/a.md" "/b.md"] (lucene/paths *idx*)))

    (lucene/clear! *idx*)

    (testing "counts zero out and paths is empty (commit + refresh applied)"
      (is (= {:num_docs 0 :num_chunks 0} (lucene/stats *idx*)))
      (is (= [] (lucene/paths *idx*))))))

(deftest clear-on-empty-is-a-noop
  (testing "clearing a fresh, empty index is harmless and leaves it empty"
    (lucene/clear! *idx*)
    (is (= {:num_docs 0 :num_chunks 0} (lucene/stats *idx*)))
    (is (= [] (lucene/paths *idx*)))))

(deftest locator-span-round-trips
  (testing "a chunk's citation span comes back on hits and on get-chunk"
    (lucene/add-chunks! *idx* [(assoc (mk-chunk "d1" "/paper.pdf" 0)
                                      :locator "page" :loc_start 4 :loc_end 5)])
    (lucene/commit! *idx*)
    (let [hit (first (lucene/keyword-search *idx* "chunk" 10 nil))]
      (is (= {:locator "page" :loc_start 4 :loc_end 5}
             (select-keys hit [:locator :loc_start :loc_end]))))
    (is (= {:locator "page" :loc_start 4 :loc_end 5}
           (select-keys (lucene/get-chunk *idx* "d1:0") [:locator :loc_start :loc_end]))))

  (testing "a chunk indexed before locators omits the keys rather than nulling them"
    ;; The client renders a bare path for these; a null would read as \"line 0\".
    (lucene/add-chunks! *idx* [(mk-chunk "d2" "/old.md" 0)])
    (lucene/commit! *idx*)
    (let [hit (first (lucene/keyword-search *idx* "old.md" 10 nil))]
      (is (= "d2:0" (:id hit)))
      (is (empty? (select-keys hit [:locator :loc_start :loc_end]))))))

(deftest reclaim-expunges-a-lightly-deleted-segment
  (testing "a deleted path leaves paths/stats even when it is a tiny share of the segment"
    ;; The regression: `paths` reads the term dictionary, which keeps a deleted
    ;; doc's terms until its segment is rewritten. Lucene's default
    ;; forceMergeDeletesPctAllowed (10%) makes forceMergeDeletes *skip* a segment
    ;; whose deletes are a small fraction of it — so the file stays in the term
    ;; dictionary, looks like an orphan to the next prune, and gets "removed"
    ;; again on every watch sweep. 1 deleted file out of 20 is 5%, under that
    ;; default, so this fails unless `open` lowers the threshold to 0.
    (let [files (mapv #(str "/f" % ".md") (range 20))]
      (lucene/add-chunks! *idx* (map-indexed (fn [i p] (mk-chunk (str "d" i) p 0)) files))
      (lucene/commit! *idx*)
      (is (= 20 (count (lucene/paths *idx*))))

      (lucene/delete-by-paths! *idx* ["/f7.md"])
      (lucene/reclaim-deletes! *idx*)

      (testing "the path is really gone, so a second prune won't rediscover it"
        (is (not (contains? (set (lucene/paths *idx*)) "/f7.md")))
        (is (= 19 (count (lucene/paths *idx*))))
        (is (= {:num_docs 19 :num_chunks 19} (lucene/stats *idx*)))))))

(deftest index-is-reusable-after-clear
  (testing "the index still accepts new chunks after a clear!"
    (seed!)
    (lucene/clear! *idx*)
    (lucene/add-chunks! *idx* [(mk-chunk "d3" "/c.md" 0)])
    (lucene/commit! *idx*)
    (is (= {:num_docs 1 :num_chunks 1} (lucene/stats *idx*)))
    (is (= ["/c.md"] (lucene/paths *idx*)))))

(deftest vectors-by-content-finds-a-file-s-vectors-by-its-hash
  ;; Two files: a two-chunk one, and one embedded by another model, whose
  ;; vectors must not be reused under the current one.
  (let [chunk (fn [doc-id path i sha spec v]
                (assoc (mk-chunk doc-id path i)
                       :text (str "chunk " i) :content_sha1 sha :model_spec spec
                       :embedding (float-array v)))]
    (lucene/add-chunks! *idx* [(chunk "d1" "/a.md" 0 "h1" "m1" [1 0 0])
                               (chunk "d1" "/a.md" 1 "h1" "m1" [0 1 0])
                               (chunk "d2" "/b.md" 0 "h2" "m0" [0 0 1])])
    (lucene/commit! *idx*)
    (testing "every chunk of the matching content comes back, keyed by its text"
      (let [m (lucene/vectors-by-content *idx* "h1" "m1")]
        (is (= #{"chunk 0" "chunk 1"} (set (keys m))))
        (is (= [1.0 0.0 0.0] (vec (get m "chunk 0"))))
        (is (= [0.0 1.0 0.0] (vec (get m "chunk 1"))))))
    (testing "vectors from another embedding model are not reused"
      (is (nil? (lucene/vectors-by-content *idx* "h2" "m1"))))
    (testing "unknown content -> nil"
      (is (nil? (lucene/vectors-by-content *idx* "nope" "m1"))))
    (testing "deleted docs don't count"
      (lucene/delete-by-paths! *idx* ["/a.md"])
      (lucene/commit! *idx*)
      (is (nil? (lucene/vectors-by-content *idx* "h1" "m1"))))))

(deftest an-index-from-before-content-key-still-takes-writes
  ;; An index written by an earlier version has content_sha1 as a stored-only
  ;; field. Lucene fixes a field's index options once it exists, so writing that
  ;; field as indexed failed every index run on such an index ("cannot change
  ;; field content_sha1 from index options=NONE ..."). Write a doc the old way,
  ;; then index on top of it.
  (let [^IndexWriter w (:writer *idx*)]
    (.addDocument w (doto (Document.)
                      (.add (StringField. "doc_id" "old" Field$Store/YES))
                      (.add (StringField. "path" "/old.md" Field$Store/YES))
                      (.add (StoredField. "content_sha1" "h0"))))
    (lucene/commit! *idx*))
  (lucene/add-chunks! *idx* [(assoc (mk-chunk "d1" "/a.md" 0)
                                    :content_sha1 "h1" :model_spec "m1"
                                    :embedding (float-array [1 0 0]))])
  (lucene/commit! *idx*)
  (testing "the new chunk is written and findable by content"
    (is (= #{"chunk 0 of /a.md"} (set (keys (lucene/vectors-by-content *idx* "h1" "m1"))))))
  (testing "the old doc, which has no content_key, just isn't found by content"
    (is (nil? (lucene/vectors-by-content *idx* "h0" nil)))))
