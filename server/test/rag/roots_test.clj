(ns rag.roots-test
  "Tests for the persisted index-root set. config/roots-file and ensure-dir! are
  redirected to a temp dir so nothing touches the real ~/.otzer."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [rag.config :as config]
            [rag.roots :as roots]))

(defn- with-temp-roots [f]
  (let [dir  (io/file (System/getProperty "java.io.tmpdir")
                      (str "roots-test-" (System/nanoTime)))
        file (io/file dir "roots.edn")]
    (.mkdirs dir)
    ;; redirect the persisted file and stub ensure-dir! so it only makes the temp
    ;; dir (the real one re-tightens ~/.otzer permissions, which we don't want here)
    (with-redefs [config/roots-file  file
                  config/ensure-dir! (fn [] (.mkdirs dir))]
      (try (f)
           (finally (.delete file) (.delete dir))))))

(deftest read-empty-when-absent
  (with-temp-roots
    (fn [] (is (= [] (roots/read-roots))))))

(deftest add-records-and-merges
  (with-temp-roots
    (fn []
      (testing "adding records each path with the recursive flag, sorted by path"
        (roots/add-roots! ["/b" "/a"] true)
        (is (= [{:path "/a" :recursive true} {:path "/b" :recursive true}]
               (roots/read-roots))))
      (testing "re-adding a path updates its flag in place (no duplicate)"
        (roots/add-roots! ["/a"] false)
        (roots/add-roots! ["/c"] true)
        (is (= [{:path "/a" :recursive false}
                {:path "/b" :recursive true}
                {:path "/c" :recursive true}]
               (roots/read-roots)))))))

(deftest corrupt-file-degrades-to-empty
  (with-temp-roots
    (fn []
      (spit config/roots-file "{not valid edn")
      (is (= [] (roots/read-roots))))))
