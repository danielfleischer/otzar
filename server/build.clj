(ns build
  "Packages the server as one self-contained jar: `clojure -T:build uber`.

  The jar is how the server ships. A release carries it as an asset next to the
  client binary, and the client boots it with plain `java -jar` — no Clojure CLI,
  no deps.edn resolution, no network on first run. Development still runs from
  source via `clojure -M:run`; nothing here is needed for that."
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/otzar-server.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber
  "Compile every namespace AOT and bundle it with its dependencies.

  `rag.main` carries (:gen-class), so the jar gets a real Main-Class and runs as
  `java -jar otzar-server.jar`. The JVM tuning that the :run alias applies in
  development is *not* baked in here — a jar can't carry -Xmx — so whoever
  launches the jar passes it; see the Go client's lifecycle package, which keeps
  the same flags in sync with deps.edn."
  [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/compile-clj {:basis basis :src-dirs ["src"] :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      'rag.main})
    (println "built" uber-file)))
