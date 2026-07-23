(ns rag.index.extract.pdf-test
  "Covers PDF extraction end to end — through real PDFBox, on PDFs this namespace
  writes itself. Fixture files are deliberately avoided: a checked-in binary is
  opaque to review and to whoever changes the unwrap heuristics next, whereas
  `pdf-of` shows exactly which lines were typeset where, which is the input the
  heuristics react to."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [rag.index.extract.pdf :as pdf])
  (:import [java.io File]
           [org.apache.pdfbox.pdmodel PDDocument PDPage PDPageContentStream]
           [org.apache.pdfbox.pdmodel.font PDType1Font Standard14Fonts$FontName]
           [org.apache.pdfbox.pdmodel.encryption AccessPermission
            StandardProtectionPolicy]))

(defn- draw-page
  "Typeset `lines` onto a new page of `doc`, one visual line each — the layout a
  real PDF arrives in."
  [^PDDocument doc lines]
  (let [page (PDPage.)]
    (.addPage doc page)
    (with-open [cs (PDPageContentStream. doc page)]
      (.beginText cs)
      (.setFont cs (PDType1Font. Standard14Fonts$FontName/HELVETICA) 12)
      (.setLeading cs 14.0)
      (.newLineAtOffset cs 50 700)
      (doseq [l lines]
        (.showText cs l)
        (.newLine cs))
      (.endText cs))))

(defn- pdf-of
  "[[line]] -> File
  Write a temp PDF, one page per inner vector of visual lines. `protect` (optional)
  applies a StandardProtectionPolicy before saving."
  ^File [pages & [protect]]
  (let [f (doto (File/createTempFile "pdf-test" ".pdf") (.deleteOnExit))]
    (with-open [doc (PDDocument.)]
      (run! #(draw-page doc %) pages)
      (when protect (.protect doc protect))
      (.save doc f))
    f))

(deftest extracts-page-text
  (testing "each page's text is extracted, separated by a form feed"
    (let [text (pdf/extract (pdf-of [["Page one text."] ["Page two text."]]))]
      (is (= ["Page one text." "Page two text."] (str/split text #"\f"))))))

(deftest rejoins-soft-wrapped-lines
  (testing "lines wrapped by the typesetter are folded back into flowing prose"
    ;; The whole point: the segmenter must see one sentence here, not two
    ;; fragments split at whatever column the PDF happened to break at.
    (is (= "Retrieval augmented generation combines search with generation."
           (pdf/extract (pdf-of [["Retrieval augmented generation combines"
                                  "search with generation."]])))))
  (testing "a word hyphenated across the break is put back together"
    (is (= "This technique combines two things."
           (pdf/extract (pdf-of [["This technique com-"
                                  "bines two things."]])))))
  (testing "a hyphenated compound that did not wrap keeps its hyphen"
    (is (= "A well-known result." (pdf/extract (pdf-of [["A well-known result."]]))))))

(deftest keeps-list-items-apart
  (testing "bullets and enumerators stay on their own lines"
    ;; Nothing here ends in punctuation, so folding these together would hand the
    ;; segmenter one page-sized \"sentence\" — the failure mode slide decks and
    ;; tables of contents would hit every time.
    (is (= ["Agenda" "- first item" "- second item" "1. numbered item"]
           (str/split (pdf/extract (pdf-of [["Agenda"
                                             "- first item"
                                             "- second item"
                                             "1. numbered item"]]))
                      #"\n")))))

(deftest skips-a-pdf-with-no-text-layer
  (testing "an empty (or scanned, image-only) PDF yields nil rather than an empty doc"
    (is (nil? (pdf/extract (pdf-of [[]]))))))

(deftest reports-a-locked-pdf-rather-than-skipping-it
  (testing "a password-protected PDF is an error, not a silent skip"
    ;; PDFBox raises an IOException subclass here, which the extractor dispatcher
    ;; would swallow as \"unreadable file\" — so pdf/extract has to re-throw it as
    ;; something the index run will surface against the file.
    (let [f  (pdf-of [["secret"]] (StandardProtectionPolicy. "owner" "user" (AccessPermission.)))
          ex (is (thrown? clojure.lang.ExceptionInfo (pdf/extract f)))]
      (is (= :pdf-locked (:type (ex-data ex))))))

  (testing "so is one whose permissions forbid extraction"
    (let [perms (doto (AccessPermission.) (.setCanExtractContent false))
          f     (pdf-of [["restricted"]] (StandardProtectionPolicy. "owner" "" perms))
          ex    (is (thrown? clojure.lang.ExceptionInfo (pdf/extract f)))]
      (is (= :pdf-locked (:type (ex-data ex)))))))
