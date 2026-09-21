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

;; ── removal ─────────────────────────────────────────────────────────────────

(deftest remove-forgets-only-what-was-named
  (with-temp-roots
    (fn []
      (roots/add-roots! ["/a" "/b" "/c"] true)
      (let [r (roots/remove-roots! ["/b"])]
        (is (= ["/b"] (:removed r)))
        (is (= ["/a" "/c"] (mapv :path (:roots r))))
        (is (= ["/a" "/c"] (mapv :path (roots/read-roots)))))))) 

(deftest removing-a-path-that-is-not-a-root-is-not-an-error
  (with-temp-roots
    (fn []
      (roots/add-roots! ["/a"] true)
      (let [r (roots/remove-roots! ["/never-added"])]
        (is (= [] (:removed r)))
        (is (= ["/a"] (mapv :path (:roots r)))))
      (testing "and the file is left exactly as it was"
        (is (= [{:path "/a" :recursive true}] (roots/read-roots)))))))

(deftest remove-does-not-validate-the-paths-it-forgets
  ;; The main reason to forget a root is that its directory is gone — refusing on
  ;; that basis would make the command useless exactly when it is needed.
  (with-temp-roots
    (fn []
      (roots/add-roots! ["/vanished/volume"] true)
      (is (= ["/vanished/volume"] (:removed (roots/remove-roots! ["/vanished/volume"]))))
      (is (= [] (roots/read-roots))))))

(deftest a-dry-run-answers-the-same-and-writes-nothing
  ;; The client confirms against this preview before deleting anything, so the
  ;; preview has to be the real answer — and must not itself be the change.
  (with-temp-roots
    (fn []
      (roots/add-roots! ["/a" "/b"] true)
      (let [before (slurp config/roots-file)
            dry    (roots/remove-roots! ["/b" "/never-added"] {:dry-run true})]
        (is (= ["/b"] (:removed dry)))
        (is (= ["/a"] (mapv :path (:roots dry))))
        (testing "roots.edn is byte-for-byte what it was"
          (is (= before (slurp config/roots-file))))
        (testing "and the real call then gives the answer the preview promised"
          (is (= dry (roots/remove-roots! ["/b" "/never-added"])))
          (is (= ["/a"] (mapv :path (roots/read-roots)))))))))

(deftest forget-all-empties-the-set
  (with-temp-roots
    (fn []
      (roots/add-roots! ["/b" "/a"] true)
      (is (= {:removed ["/a" "/b"]} (roots/forget-all!)))
      (is (= [] (roots/read-roots)))
      (testing "and is harmless when there was nothing recorded"
        (is (= {:removed []} (roots/forget-all!)))))))

;; ── atomic writes ───────────────────────────────────────────────────────────

(deftest a-write-leaves-no-temp-file-behind
  (with-temp-roots
    (fn []
      (roots/add-roots! ["/a"] true)
      (roots/remove-roots! ["/a"])
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
