(ns rag.index.lucene-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [rag.index.lucene :as lucene])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *idx* nil)

(defn- tmp-index-fixture
  "Open a Lucene index in a throwaway directory for one test, then close it and
  delete the directory. Isolated from ~/.otzer — nothing here touches the real
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

(deftest index-is-reusable-after-clear
  (testing "the index still accepts new chunks after a clear!"
    (seed!)
    (lucene/clear! *idx*)
    (lucene/add-chunks! *idx* [(mk-chunk "d3" "/c.md" 0)])
    (lucene/commit! *idx*)
    (is (= {:num_docs 1 :num_chunks 1} (lucene/stats *idx*)))
    (is (= ["/c.md"] (lucene/paths *idx*)))))
