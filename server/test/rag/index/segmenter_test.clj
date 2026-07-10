(ns rag.index.segmenter-test
  (:require [clojure.test :refer [deftest is testing]]
            [rag.index.segmenter :as segmenter]))

(defn- texts
  "Just the sentence strings, dropping line numbers — for the shape-agnostic cases."
  [segments]
  (mapv :text segments))

(deftest blank-input
  (testing "blank/nil text yields no sentences"
    (is (= [] (segmenter/segment nil)))
    (is (= [] (segmenter/segment "")))
    (is (= [] (segmenter/segment "   \n\t ")))))

(deftest splits-english-prose
  (testing "English prose splits into one map per sentence, all on line 1"
    (is (= [{:text "The cat sat on the mat." :line 1}
            {:text "The dog ran in the park." :line 1}
            {:text "Birds flew overhead." :line 1}]
           (segmenter/segment "The cat sat on the mat. The dog ran in the park. Birds flew overhead.")))))

(deftest splits-hebrew-prose
  (testing "Hebrew prose splits correctly (the reason we use BreakIterator, not OpenNLP)"
    (is (= ["החתול ישב על המחצלת." "הכלב רץ בפארק." "ציפורים עפו למעלה!"]
           (texts (segmenter/segment "החתול ישב על המחצלת. הכלב רץ בפארק. ציפורים עפו למעלה!"))))))

(deftest trims-sentences
  (testing "surrounding whitespace is stripped from each sentence"
    (is (= ["Hello." "World."] (texts (segmenter/segment "  Hello.   World.  "))))))

(deftest rule-based-abbreviation-limitation
  (testing "known limitation: period-abbreviations split (rule-based, not statistical)"
    ;; Documented, not a bug — see segmenter ns docstring. Harmless for Hebrew.
    (is (= ["Dr." "Smith went to Washington."]
           (texts (segmenter/segment "Dr. Smith went to Washington."))))))

(deftest newline-is-a-hard-boundary
  (testing "every line break splits, even without terminating punctuation"
    (is (= ["buy milk" "walk the dog" "call mom"]
           (texts (segmenter/segment "buy milk\nwalk the dog\ncall mom"))))
    (testing "a checklist with no periods becomes one sentence per line"
      (is (= ["- item one" "- item two" "- item three"]
             (texts (segmenter/segment "- item one\n- item two\n- item three")))))
    (testing "blank lines and \\r\\n / \\r are boundaries too, and produce no empties"
      (is (= ["first block" "second block"]
             (texts (segmenter/segment "first block\n\nsecond block"))))
      (is (= ["a" "b" "c"] (texts (segmenter/segment "a\r\nb\rc")))))
    (testing "punctuation still splits within a line"
      (is (= ["One." "Two." "Three."]
             (texts (segmenter/segment "One. Two.\nThree.")))))))

(deftest tracks-source-line
  (testing ":line is the 1-based source line, shared by sentences on the same line"
    (is (= [{:text "One." :line 1} {:text "Two." :line 1} {:text "Three." :line 2}]
           (segmenter/segment "One. Two.\nThree."))))
  (testing "blank lines still advance the line counter (a \\R is a line, even if empty)"
    (is (= [{:text "first block" :line 1} {:text "second block" :line 3}]
           (segmenter/segment "first block\n\nsecond block"))))
  (testing "\\r\\n counts as one line break, \\r as one too"
    (is (= [{:text "a" :line 1} {:text "b" :line 2} {:text "c" :line 3}]
           (segmenter/segment "a\r\nb\rc")))))
