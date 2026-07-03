(ns rag.index.segmenter-test
  (:require [clojure.test :refer [deftest is testing]]
            [rag.index.segmenter :as segmenter]))

(deftest blank-input
  (testing "blank/nil text yields no sentences"
    (is (= [] (segmenter/segment nil)))
    (is (= [] (segmenter/segment "")))
    (is (= [] (segmenter/segment "   \n\t ")))))

(deftest splits-english-prose
  (testing "English prose splits into one string per sentence"
    (is (= ["The cat sat on the mat." "The dog ran in the park." "Birds flew overhead."]
           (segmenter/segment "The cat sat on the mat. The dog ran in the park. Birds flew overhead.")))))

(deftest splits-hebrew-prose
  (testing "Hebrew prose splits correctly (the reason we use BreakIterator, not OpenNLP)"
    (is (= ["החתול ישב על המחצלת." "הכלב רץ בפארק." "ציפורים עפו למעלה!"]
           (segmenter/segment "החתול ישב על המחצלת. הכלב רץ בפארק. ציפורים עפו למעלה!")))))

(deftest trims-sentences
  (testing "surrounding whitespace is stripped from each sentence"
    (is (= ["Hello." "World."] (segmenter/segment "  Hello.   World.  ")))))

(deftest rule-based-abbreviation-limitation
  (testing "known limitation: period-abbreviations split (rule-based, not statistical)"
    ;; Documented, not a bug — see segmenter ns docstring. Harmless for Hebrew.
    (is (= ["Dr." "Smith went to Washington."]
           (segmenter/segment "Dr. Smith went to Washington.")))))
