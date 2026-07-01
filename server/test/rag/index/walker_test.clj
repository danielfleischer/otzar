(ns rag.index.walker-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [rag.index.walker :as walker])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *root* nil)

(defn- touch [dir & segments]
  (let [f (apply io/file dir segments)]
    (io/make-parents f)
    (spit f "content")
    f))

(defn- tmp-tree-fixture [f]
  (let [root (.toFile (Files/createTempDirectory "walker-test" (make-array FileAttribute 0)))]
    (touch root "a.md")
    (touch root "b.txt")
    (touch root "ignore.png")                 ; not a text extension
    (touch root "sub" "c.clj")                ; nested source file
    (touch root "node_modules" "d.md")        ; inside a skip-dir
    (touch root ".git" "e.txt")               ; inside a hidden skip-dir
    (binding [*root* root]
      (try (f) (finally (run! io/delete-file (reverse (file-seq root))))))))

(use-fixtures :each tmp-tree-fixture)

(defn- names [files]
  (set (map #(.getName ^java.io.File %) files)))

(deftest recursive-walk
  (testing "descends subdirs, keeps text/code files, drops non-text and skip-dirs"
    (is (= #{"a.md" "b.txt" "c.clj"}
           (names (walker/walk *root* {:recursive true}))))))

(deftest non-recursive-walk
  (testing "top level only — nested c.clj is not reached"
    (is (= #{"a.md" "b.txt"}
           (names (walker/walk *root* {:recursive false}))))))

(deftest single-file
  (testing "an indexable file yields itself; a non-text file yields nothing"
    (is (= #{"a.md"} (names (walker/walk (io/file *root* "a.md") {}))))
    (is (= #{} (names (walker/walk (io/file *root* "ignore.png") {}))))))

(deftest missing-path
  (testing "nonexistent path yields nothing"
    (is (= [] (walker/walk (io/file *root* "nope") {})))))
