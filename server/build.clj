(ns build
  "Packages the server as one self-contained jar: `clojure -T:build uber`.

  The jar is how the server ships. A release carries it as an asset next to the
  client binary, and the client boots it with plain `java -jar` — no Clojure CLI,
  no deps.edn resolution, no network on first run. Development still runs from
  source via `clojure -M:run`; nothing here is needed for that."
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private uber-file "target/otzar-server.jar")

(def ^:private version-resource
  "Classpath name of the stamped version, read back at runtime by rag.config.
  A plain classpath resource rather than a manifest attribute: the server only
  has to slurp it, with no need to locate its own jar first."
  "otzar-version.txt")

(defn- version
  "The version to stamp into the jar: the explicit :version argument, else what
  git says about this checkout, else \"dev\".

  The Makefile passes the same string it stamps into the Go client, so the two
  halves of a release agree on what they are — that agreement is what the
  client's mismatch warning checks. Building the jar by hand still yields
  something honest instead of a lie."
  [opts]
  (or (:version opts)
      (try (b/git-process {:git-args "describe --tags --always --dirty"})
           (catch Exception _ nil))
      "dev"))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber
  "Compile every namespace AOT and bundle it with its dependencies.

  `rag.main` carries (:gen-class), so the jar gets a real Main-Class and runs as
  `java -jar otzar-server.jar`. The JVM tuning that the :run alias applies in
  development is *not* baked in here — a jar can't carry -Xmx — so whoever
  launches the jar passes it; see the Go client's lifecycle package, which keeps
  the same flags in sync with deps.edn.

  `:version` (optional) is the string GET /health reports; see the version fn."
  [opts]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})
        v     (version opts)]
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/compile-clj {:basis basis :src-dirs ["src"] :class-dir class-dir})
    (spit (str class-dir "/" version-resource) v)
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      'rag.main})
    (println "built" uber-file v)))
