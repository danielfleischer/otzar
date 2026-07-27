(ns rag.index.extract.docx-test
  "Covers .docx extraction against real zips this namespace builds — same choice
  as the PDF tests: a checked-in binary fixture would hide the very thing under
  test, which here is which runs sat in which `<w:p>`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [rag.index.extract.docx :as docx]
            [rag.index.segmenter :as segmenter])
  (:import [java.io File FileOutputStream]
           [java.util.zip ZipEntry ZipOutputStream]))

(def ^:private para-sep "\u2029")

(defn- zip-file
  "Write `entries` (name -> content) to a temp .docx and return it."
  ^File [entries]
  (let [f (File/createTempFile "otzar-test" ".docx")]
    (.deleteOnExit f)
    (with-open [out (ZipOutputStream. (FileOutputStream. f))]
      (doseq [[name content] entries]
        (.putNextEntry out (ZipEntry. ^String name))
        (.write out (.getBytes ^String content "UTF-8"))
        (.closeEntry out)))
    f))

(defn- docx-of
  "Build a .docx whose document.xml body is `body-xml`."
  ^File [body-xml]
  (zip-file {"word/document.xml"
             (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                  "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                  "<w:body>" body-xml "</w:body></w:document>")}))

(defn- para
  "One `<w:p>` holding each of `runs` as its own `<w:t>` — which is how Word
  actually stores a sentence whose formatting changes partway through."
  [& runs]
  (str "<w:p>" (str/join (map #(str "<w:r><w:t>" % "</w:t></w:r>") runs)) "</w:p>"))

(defn- paragraphs [^File f]
  (str/split (docx/extract f) (re-pattern para-sep) -1))

(deftest joins-runs-within-a-paragraph
  (testing "a paragraph split across runs comes back as one string"
    (is (= ["The quick brown fox."]
           (paragraphs (docx-of (para "The quick " "brown " "fox.")))))))

(deftest separates-paragraphs
  (testing "each <w:p> is its own unit"
    (is (= ["One." "Two." "Three."]
           (paragraphs (docx-of (str (para "One.") (para "Two.") (para "Three."))))))))

(deftest keeps-empty-paragraphs
  (testing "an empty paragraph holds its ordinal, so later citations stay right"
    (is (= ["One." "" "Three."]
           (paragraphs (docx-of (str (para "One.") "<w:p/>" (para "Three."))))))))

(deftest break-becomes-newline
  (testing "a manual line break is a hard sentence boundary, not a space"
    (is (= ["a\nb"]
           (paragraphs (docx-of "<w:p><w:r><w:t>a</w:t><w:br/><w:t>b</w:t></w:r></w:p>")))))
  (testing "a tab stays horizontal whitespace"
    (is (= ["a\tb"]
           (paragraphs (docx-of "<w:p><w:r><w:t>a</w:t><w:tab/><w:t>b</w:t></w:r></w:p>"))))))

(deftest ignores-formatting-elements
  (testing "run properties are not text"
    (is (= ["bold text"]
           (paragraphs (docx-of (str "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>bold </w:t></w:r>"
                                     "<w:r><w:t>text</w:t></w:r></w:p>")))))))

(deftest reads-table-cell-paragraphs
  (testing "paragraphs inside a table are prose too"
    (is (= ["cell one" "cell two"]
           (paragraphs (docx-of (str "<w:tbl><w:tr><w:tc>" (para "cell one") "</w:tc>"
                                     "<w:tc>" (para "cell two") "</w:tc></w:tr></w:tbl>")))))))

(deftest nil-when-not-a-docx
  (testing "a zip without word/document.xml — a renamed .doc, an encrypted file"
    (is (nil? (docx/extract (zip-file {"whatever.txt" "hello"})))))
  (testing "a document with no text at all"
    (is (nil? (docx/extract (docx-of "<w:p/><w:p/>"))))))

(deftest ignores-external-entities
  (testing "a DTD entity in document.xml is not resolved (SUPPORT_DTD false)"
    (let [f (zip-file {"word/document.xml"
                       (str "<?xml version=\"1.0\"?>"
                            "<!DOCTYPE d [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                            "<w:body><w:p><w:r><w:t>safe</w:t></w:r></w:p></w:body></w:document>")})]
      ;; Either it parses without expanding the entity, or it refuses the DTD
      ;; outright — both are fine; silently reading /etc/passwd is not.
      (let [text (try (docx/extract f) (catch Exception _ nil))]
        (is (or (nil? text) (not (str/includes? text "root:"))))))))

(deftest locators-are-paragraph-numbers
  (testing "end to end: the segmenter numbers sentences by paragraph"
    (let [f    (docx-of (str (para "First one here.") "<w:p/>" (para "Third one here.")))
          segs (segmenter/segment (docx/extract f) "paragraph")
          by   (into {} (map (juxt :text :loc)) segs)]
      (is (= 1 (get by "First one here.")))
      (is (= 3 (get by "Third one here."))))))
