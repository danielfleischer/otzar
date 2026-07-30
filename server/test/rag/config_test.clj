(ns rag.config-test
  "Tests for the facts rag.config derives about the running process. The paths
  and permissions it also owns are covered by rag.fs-safety-test."
  (:require [clojure.test :refer [deftest is testing]]
            [rag.config :as config]))

(deftest source-of-boot-shapes
  (testing "java -jar: one entry, and it is the jar"
    (is (= "/opt/otzar/otzar-server.jar"
           (config/source-of ["/opt/otzar/otzar-server.jar"]))))

  (testing "source run: the project's own dirs lead, so the tree is src's parent"
    (is (= "/home/me/my-rag/server (source)"
           (config/source-of ["/home/me/my-rag/server/src"
                              "/home/me/my-rag/server/resources"
                              "/home/me/.m2/repository/x/x.jar"]))))

  (testing "relative entries are absolutised — the client reads this from elsewhere"
    (let [source (config/source-of ["src" "resources"])]
      (is (.startsWith source "/"))
      (is (.endsWith source " (source)"))))

  (testing "a bare -cp of jars is a jar boot we can't name, not a fake source tree"
    (is (= "unknown" (config/source-of ["/a/x.jar" "/b/y.jar"]))))

  (testing "an empty classpath is unknown rather than a crash"
    (is (= "unknown" (config/source-of [])))))

(deftest launch-source-describes-this-process
  ;; The suite runs from the source tree, so that is what the live value must say.
  (is (.endsWith config/launch-source " (source)")))
