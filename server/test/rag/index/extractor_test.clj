(ns rag.index.extractor-test
  "Covers the registry seam: dispatch by extension, the shared size guard, and
  the text format's own binary sniff. The registry invariants are asserted too —
  they are what a contributor adding a format is most likely to break."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rag.index.extractor :as extractor])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *root* nil)

(defn- tmp-dir-fixture [f]
  (let [root (.toFile (Files/createTempDirectory "extractor-test" (make-array FileAttribute 0)))]
    (binding [*root* root]
      (try (f) (finally (run! io/delete-file (reverse (file-seq root))))))))

(use-fixtures :each tmp-dir-fixture)

(defn- write
  "Write `content` (String or byte-array) to <tmp>/name and return the File."
  [name content]
  (let [f (io/file *root* name)]
    (if (string? content)
      (spit f content)
      (with-open [o (io/output-stream f)] (.write o ^bytes content)))
    f))

(deftest ext-parsing
  (testing "lower-cased, dot-stripped, last segment wins"
    (is (= "md"   (extractor/ext (io/file "/tmp/a.MD"))))
    (is (= "clj"  (extractor/ext (io/file "/tmp/a.b.clj")))))
  (testing "nil when there is nothing to dispatch on"
    (is (nil? (extractor/ext (io/file "/tmp/README"))))
    (is (nil? (extractor/ext (io/file "/tmp/.bashrc"))))))

(deftest dispatch
  (testing "a registered extension extracts"
    (is (= "hello" (extractor/extract (write "a.md" "hello")))))
  (testing "an unclaimed extension is skipped without reading the file"
    (is (nil? (extractor/extract (write "a.png" "hello")))))
  (testing "no extension is skipped"
    (is (nil? (extractor/extract (write "README" "hello")))))
  (testing "a nonexistent file is skipped, not thrown"
    (is (nil? (extractor/extract (io/file *root* "gone.md"))))))

(deftest size-guard
  (testing "empty files are skipped"
    (is (nil? (extractor/extract (write "empty.md" "")))))
  (testing "files past the format's ceiling are skipped"
    (let [big (write "big.md" (byte-array (inc (* 5 1024 1024)) (byte 65)))]
      (is (nil? (extractor/extract big))))))

(deftest text-format-sniffs-binary
  (testing "a NUL byte in the first 8 KiB means it is not text we want"
    (is (nil? (extractor/extract (write "sneaky.md" (byte-array [72 105 0 33]))))))
  (testing "a NUL past the sniff window does not disqualify the file"
    (let [bs (byte-array (+ 8192 2) (byte 65))]
      (aset-byte bs 8193 (byte 0))
      (is (some? (extractor/extract (write "late-nul.md" bs))))))
  (testing "UTF-8 is decoded, not mangled — the corpus is multilingual"
    (is (= "שלום" (extractor/extract (write "he.md" "שלום"))))))

(deftest registry-invariants
  (testing "supported-extensions is exactly the union of the formats' claims"
    (is (= (into #{} (mapcat :extensions) extractor/formats)
           extractor/supported-extensions)))
  (testing "no two formats claim the same extension"
    (is (= (count extractor/supported-extensions)
           (reduce + (map (comp count :extensions) extractor/formats)))))
  (testing "every format is complete enough to dispatch to"
    (doseq [{:keys [name extensions extract]} extractor/formats]
      (is (keyword? name))
      (is (seq extensions) (str name " claims no extensions"))
      (is (every? #(= % (str/lower-case %)) extensions)
          (str name " must claim lower-cased extensions — dispatch lower-cases"))
      (is (ifn? extract) (str name " has no :extract fn")))))
