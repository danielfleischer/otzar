(ns rag.query.hybrid-test
  "Tests for Reciprocal Rank Fusion (rag.query.hybrid/fuse). These pin the
  contract in the fuse docstring: rank-based scoring, dedup by :id across the
  two lists, additive fusion, the :score_breakdown shape, ordering, and
  truncation to k. Score-value assertions are derived from the public
  rrf-k / vector-weight constants so they track deliberate tuning of those."
  (:require [clojure.test :refer [deftest is testing]]
            [rag.query.hybrid :as h]))

(defn- hit
  "A minimal retriever hit map, as rag.index.lucene produces one."
  [id score]
  {:id id :doc_id id :path (str "/" id) :score score
   :text (str "text-" id) :chunk_idx 0 :mtime 1700000000000})

(defn- kw-contrib
  "RRF contribution of a keyword hit at 0-based rank `i`."
  [i] (/ 1.0 (+ h/rrf-k i)))

(defn- vec-contrib
  "RRF contribution of a vector hit at 0-based rank `i` (weighted)."
  [i] (* h/vector-weight (/ 1.0 (+ h/rrf-k i))))

(defn- approx= [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

(defn- score-of [out id]
  (:score (first (filter #(= id (:id %)) out))))

(deftest fuse-empty
  (testing "two empty lists fuse to nothing"
    (is (empty? (h/fuse [] [] 10)))))

(deftest fuse-keyword-only
  (let [kw  [(hit "a" 5.0) (hit "b" 3.0) (hit "c" 1.0)]
        out (h/fuse kw [] 10)]
    (testing "every hit survives, in the retriever's rank order"
      (is (= ["a" "b" "c"] (mapv :id out))))
    (testing "breakdown carries the raw keyword score and a nil vector score"
      (is (= {:keyword 5.0 :vector nil} (:score_breakdown (first out)))))
    (testing "fused score is 1/(rrf-k + rank), highest at rank 0"
      (is (approx= (kw-contrib 0) (:score (nth out 0))))
      (is (approx= (kw-contrib 1) (:score (nth out 1))))
      (is (approx= (kw-contrib 2) (:score (nth out 2)))))))

(deftest fuse-vector-only
  (let [vc  [(hit "x" 0.9) (hit "y" 0.7)]
        out (h/fuse [] vc 10)]
    (testing "every hit survives, in the retriever's rank order"
      (is (= ["x" "y"] (mapv :id out))))
    (testing "breakdown carries a nil keyword score and the raw vector score"
      (is (= {:keyword nil :vector 0.9} (:score_breakdown (first out)))))
    (testing "fused score is the weighted vector contribution"
      (is (approx= (vec-contrib 0) (:score (nth out 0))))
      (is (approx= (vec-contrib 1) (:score (nth out 1)))))))

(deftest fuse-dedup-across-lists
  (let [kw  [(hit "a" 5.0) (hit "shared" 3.0)]   ; shared at rank 1
        vc  [(hit "shared" 0.9) (hit "c" 0.8)]   ; shared at rank 0
        out (h/fuse kw vc 10)]
    (testing "a chunk in both lists appears exactly once"
      (is (= 1 (count (filter #(= "shared" (:id %)) out)))))
    (testing "the output is the union of the two lists' ids"
      (is (= #{"a" "shared" "c"} (set (map :id out)))))
    (testing "the shared chunk's breakdown holds both raw scores"
      (is (= {:keyword 3.0 :vector 0.9} (:score_breakdown (first (filter #(= "shared" (:id %)) out))))))
    (testing "appearing in both lists sums the contributions, outranking singletons"
      (is (approx= (+ (kw-contrib 1) (vec-contrib 0)) (score-of out "shared")))
      (is (= ["shared" "c" "a"] (mapv :id out))))))

(deftest fuse-score-additive
  (testing "a shared chunk's fused score equals its per-list contributions summed"
    (let [kw       [(hit "a" 5.0) (hit "shared" 3.0)]
          vc       [(hit "shared" 0.9) (hit "c" 0.8)]
          combined (score-of (h/fuse kw vc 10) "shared")
          kw-part  (score-of (h/fuse kw [] 10) "shared")
          vc-part  (score-of (h/fuse [] vc 10) "shared")]
      (is (approx= combined (+ kw-part vc-part))))))

(deftest fuse-truncates-to-k
  (let [kw  (mapv #(hit (str "k" %) (double (- 10 %))) (range 5))
        out (h/fuse kw [] 3)]
    (testing "at most k hits come back"
      (is (= 3 (count out))))
    (testing "and they are the top-k by fused score"
      (is (= ["k0" "k1" "k2"] (mapv :id out))))))

(deftest fuse-orders-by-score-descending
  (let [kw  [(hit "a" 1.0) (hit "b" 1.0) (hit "c" 1.0)]
        vc  [(hit "c" 1.0) (hit "b" 1.0)]
        out (h/fuse kw vc 10)]
    (testing "results are sorted best-first by fused score"
      (is (apply >= (map :score out))))))

(deftest fuse-preserves-hit-fields
  (testing "fusion carries through the chunk's stored fields untouched"
    (let [out (h/fuse [(hit "a" 5.0)] [] 10)
          h1  (first out)]
      (is (= "a"             (:id h1)))
      (is (= "a"             (:doc_id h1)))
      (is (= "/a"            (:path h1)))
      (is (= "text-a"        (:text h1)))
      (is (= 0               (:chunk_idx h1)))
      (is (= 1700000000000   (:mtime h1))))))
