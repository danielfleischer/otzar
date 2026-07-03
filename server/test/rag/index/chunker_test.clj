(ns rag.index.chunker-test
  (:require [clojure.test :refer [deftest is testing]]
            [rag.index.chunker :as chunker]))

(deftest pack-empty
  (testing "nil/blank input yields no chunks"
    (is (= [] (chunker/pack nil)))
    (is (= [] (chunker/pack [])))
    (is (= [] (chunker/pack ["" "   "])))))

(deftest pack-short-doc-single-chunk
  (testing "≤ core sentences → one chunk at idx 0, sentences space-joined"
    (is (= [{:chunk_idx 0 :text "One. Two. Three."}]
           (chunker/pack ["One." "Two." "Three."])))))

(deftest pack-windows-with-context
  (testing "core=3 plus 1 sentence of context each side, clamped at the ends"
    (let [s      (mapv #(str "s" %) (range 9))   ; ["s0" "s1" ... "s8"]
          chunks (chunker/pack s)]
      (is (= [0 1 2] (map :chunk_idx chunks)))
      (is (= ["s0 s1 s2 s3"        ; window 0: no sentence before, +1 after
              "s2 s3 s4 s5 s6"     ; window 1: +1 before, +1 after
              "s5 s6 s7 s8"]       ; window 2: +1 before, no sentence after
             (map :text chunks))))))
