(ns rag.main-test
  (:require [clojure.test :refer [deftest is]]
            [rag.main]))

(deftest unauthenticated-http-api-is-loopback-only
  (let [options @#'rag.main/http-options]
    (is (= "127.0.0.1" (:host options)))
    (is (= 0 (:port options)))))
