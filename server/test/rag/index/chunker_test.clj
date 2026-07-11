(ns rag.index.chunker-test
  (:require [clojure.test :refer [deftest is testing]]
            [rag.index.chunker :as chunker]))

(defn- sent
  "A sentence map like segmenter/segment emits. One arg puts it on line 1."
  ([text] (sent text 1))
  ([text line] {:text text :line line}))

(deftest pack-empty
  (testing "nil/blank input yields no chunks"
    (is (= [] (chunker/pack nil)))
    (is (= [] (chunker/pack [])))
    (is (= [] (chunker/pack [(sent "") (sent "   ")])))))

;; Windowing tests pass :core/:context explicitly so they exercise the packing
;; logic independent of whatever chunker/default-opts happens to be.
(def ^:private opts {:core 3 :context 1})

(deftest pack-short-doc-single-chunk
  (testing "≤ core sentences → one chunk at idx 0, sentences space-joined"
    (is (= [{:chunk_idx 0 :text "One. Two. Three." :line_start 1 :line_end 1}]
           (chunker/pack [(sent "One.") (sent "Two.") (sent "Three.")] opts)))))

(deftest pack-windows-with-context
  (testing "core=3 plus 1 sentence of context each side, clamped at the ends"
    (let [s      (mapv #(sent (str "s" %)) (range 9))   ; all on line 1
          chunks (chunker/pack s opts)]
      (is (= [0 1 2] (map :chunk_idx chunks)))
      (is (= ["s0 s1 s2 s3"        ; window 0: no sentence before, +1 after
              "s2 s3 s4 s5 s6"     ; window 1: +1 before, +1 after
              "s5 s6 s7 s8"]       ; window 2: +1 before, no sentence after
             (map :text chunks))))))

(deftest pack-line-ranges
  (testing "each chunk's [:line_start :line_end] spans its window's first→last line"
    (let [s      (mapv #(sent (str "s" %) (inc %)) (range 9))  ; s_i on line i+1
          chunks (chunker/pack s opts)]
      (is (= [[1 4] [3 7] [6 9]]
             (map (juxt :line_start :line_end) chunks)))))
  (testing "sentences sharing a line collapse the range to that line"
    (is (= [{:line_start 1 :line_end 1}]
           (map #(select-keys % [:line_start :line_end])
                (chunker/pack [(sent "One." 1) (sent "Two." 1)]))))))
