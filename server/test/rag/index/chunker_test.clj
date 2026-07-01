(ns rag.index.chunker-test
  (:require [clojure.test :refer [deftest is testing]]
            [rag.index.chunker :as chunker]))

(deftest empty-input
  (testing "blank and empty text produce no chunks"
    (is (= [] (chunker/chunk-text "")))
    (is (= [] (chunker/chunk-text "   \n\t ")))
    (is (= [] (chunker/chunk-text nil)))))

(deftest single-chunk
  (testing "text under one window is a single chunk with idx 0"
    (let [chunks (chunker/chunk-text "the quick brown fox")]
      (is (= 1 (count chunks)))
      (is (= {:chunk_idx 0 :text "the quick brown fox"} (first chunks))))))

(deftest whitespace-collapsed
  (testing "runs of whitespace collapse to single spaces"
    (is (= "a b c" (:text (first (chunker/chunk-text "a\n\n  b\t c")))))))

(deftest windowing-and-overlap
  (testing "windows advance by (size - overlap) and share `overlap` tokens"
    (let [tokens (map str (range 10))                 ; "0" .. "9"
          text   (clojure.string/join " " tokens)
          chunks (chunker/chunk-text text {:chunk-size 4 :overlap 2})]
      ;; step = 2 → windows start at 0,2,4,6 (start 6 already reaches the end)
      (is (= [0 1 2 3] (map :chunk_idx chunks)))
      (is (= "0 1 2 3" (:text (nth chunks 0))))
      (is (= "2 3 4 5" (:text (nth chunks 1))))
      (is (= "6 7 8 9" (:text (last chunks))))
      ;; consecutive windows overlap by 2 tokens
      (is (= ["2" "3"] (take-last 2 (clojure.string/split (:text (nth chunks 0)) #" "))))
      (is (= ["2" "3"] (take 2 (clojure.string/split (:text (nth chunks 1)) #" ")))))))

(deftest no-infinite-loop-on-zero-step
  (testing "overlap >= size still terminates (step floored at 1)"
    (let [text (clojure.string/join " " (map str (range 5)))]
      ;; step floored at 1 → windows start at 0,1,2,3 (start 3 reaches the end)
      (is (= 4 (count (chunker/chunk-text text {:chunk-size 2 :overlap 5})))))))
