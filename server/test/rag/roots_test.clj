(ns rag.roots-test
  "Tests for the persisted index-root set. config/roots-file and ensure-dir! are
  redirected to a temp dir so nothing touches the real ~/.otzar."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [rag.config :as config]
            [rag.roots :as roots]))

;; unusable is a pure predicate over the filesystem — no persisted state involved,
;; so these cases need none of the with-temp-roots redirection.
(def ^:private unusable-paths roots/unusable)

(defn- with-temp-roots [f]
  (let [dir  (io/file (System/getProperty "java.io.tmpdir")
                      (str "roots-test-" (System/nanoTime)))
        file (io/file dir "roots.edn")]
    (.mkdirs dir)
    ;; redirect the persisted file and stub ensure-dir! so it only makes the temp
    ;; dir (the real one re-tightens ~/.otzar permissions, which we don't want here)
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

;; ── validation ──────────────────────────────────────────────────────────────
;; A root is *persisted*, so a typo isn't a one-off no-op: it is saved forever,
;; indexes nothing, and — because a root missing from disk is indistinguishable
;; from an unmounted volume — stops the watch sweep pruning from then on.

(deftest unusable-catches-paths-that-cannot-be-roots
  (testing "paths that exist and can be read are fine"
    (let [f (java.io.File/createTempFile "roots-test" ".md")]
      (try
        (is (= [] (unusable-paths [(.getAbsolutePath f)])))
        (is (= [] (unusable-paths [(.getAbsolutePath (.getParentFile f))])))
        (finally (.delete f)))))

  (testing "a path that isn't on disk is reported, with a reason"
    (let [bad (unusable-paths ["/definitely/not/here"])]
      (is (= ["/definitely/not/here"] (mapv :path bad)))
      (is (= ["no such file or directory"] (mapv :reason bad)))))

  (testing "every bad path is named, not just the first"
    (is (= 2 (count (unusable-paths ["/nope/a" "/nope/b"])))))

  (testing "the good ones in a mixed list are not reported"
    (let [f (java.io.File/createTempFile "roots-test" ".md")]
      (try
        (is (= ["/nope/a"] (mapv :path (unusable-paths [(.getAbsolutePath f) "/nope/a"]))))
        (finally (.delete f)))))

  (testing "empty in, empty out"
    (is (= [] (unusable-paths [])))))

;; ── atomic writes ───────────────────────────────────────────────────────────

(deftest a-write-leaves-no-temp-file-behind
  (with-temp-roots
    (fn []
      (roots/add-roots! ["/a"] true)
      (roots/add-roots! ["/b"] false)
      (let [dir (.getParentFile config/roots-file)]
        (is (= ["roots.edn"] (sort (mapv #(.getName ^java.io.File %) (.listFiles dir)))))))))

(deftest a-reader-never-sees-a-half-written-file
  ;; The point of the temp-file-and-rename: roots.edn is written on one thread and
  ;; read on another (the watch sweep), and read-roots degrades a truncated file to
  ;; "no roots" — silently forgetting every tree the user asked to keep indexed.
  ;; Hammering a reader against a writer must never produce that.
  (with-temp-roots
    (fn []
      (roots/add-roots! ["/a" "/b" "/c"] true)
      (let [stop    (atom false)
            seen    (atom #{})
            reader  (future (while (not @stop)
                              (swap! seen conj (count (roots/read-roots))))
                            @seen)]
        (dotimes [i 200]
          (roots/add-roots! [(str "/a" i)] true))
        (reset! stop true)
        (let [counts @reader]
          (is (seq counts))
          (testing "every observed read was a complete root set, never an empty one"
            (is (not (contains? counts 0)))))))))
