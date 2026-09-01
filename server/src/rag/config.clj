(ns rag.config
  "Runtime paths shared with the Go client. The contract: the server owns the
  port file (it picks a free port and writes the actual one); the client owns
  the pid file. Both live under ~/.otzar/. Also the two facts about this
  process that only it can report: its version and where it was booted from."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.nio.file.attribute PosixFilePermissions]))

(def version
  "This build's version, reported by GET /health.

  build.clj stamps it into the jar from the same `git describe` the Makefile
  stamps into the client binary. Running from source there is no resource to
  read, so it is \"dev\" — the same word an unstamped `go build` client reports,
  and the value both sides treat as \"don't compare versions\"."
  (or (some-> (io/resource "otzar-version.txt") slurp str/trim not-empty) "dev"))

(defn- classpath-entries []
  (->> (str/split (or (System/getProperty "java.class.path") "")
                  (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))
       (remove str/blank?)))

(defn source-of
  "-> String
  Where a process running with classpath `entries` was booted from.

  The two boot shapes are distinguishable from the classpath alone: `java -jar`
  puts the jar there and nothing else, while a source run leads with the
  project's own directories (src, resources) ahead of its dependency jars. Paths
  are absolutised because they arrive relative to the process's working
  directory, and the client displays them from some other directory entirely."
  [entries]
  (let [jar? #(str/ends-with? % ".jar")
        abs  #(.getAbsoluteFile (io/file %))]
    (cond
      (and (= 1 (count entries)) (jar? (first entries)))
      (str (abs (first entries)))

      ;; The source tree is the parent of the first classpath directory.
      (seq (remove jar? entries))
      (str (.getParent (abs (first (remove jar? entries)))) " (source)")

      :else "unknown")))

(def launch-source
  "Where this process's code is actually running from, reported by GET /health.

  The client can only *predict* this — `lifecycle.resolveServer` answers what it
  would boot from the current directory, which stops matching reality the moment
  anything (an upgrade, a stale daemon, a shell sitting in a checkout) puts the
  two out of step. Only the running process knows, so it says."
  (source-of (classpath-entries)))

(def home-dir (System/getProperty "user.home"))
(def otzar-dir (io/file home-dir ".otzar"))
(def port-file (io/file otzar-dir "server.port"))
(def index-dir (io/file otzar-dir "index"))
(def roots-file
  "Persisted set of index roots — the paths /index was asked to index, so the
  watch sweep (rag.watch) knows what to re-scan. See rag.roots."
  (io/file otzar-dir "roots.edn"))
(def watch-file
  "Persisted watch toggle (enabled + interval), read at boot so the background
  sweep survives a server restart. See rag.watch."
  (io/file otzar-dir "watch.edn"))

(def ^:private owner-only
  "rwx------ — the owner may read/list/enter ~/.otzar; nobody else can."
  (PosixFilePermissions/fromString "rwx------"))

(defn ensure-dir!
  "-> Path
  Create ~/.otzar if absent and lock it to the owner, re-tightening on every
  boot. Everything inside (index/, *.pid, *.port, *.log) inherits the protection:
  with the parent unenterable by others, they can't reach the contents."
  []
  (.mkdirs otzar-dir)
  (Files/setPosixFilePermissions (.toPath otzar-dir) owner-only))

(defn spit-atomic!
  "File, str -> nil
  Write `content` to `file` so a reader only ever sees the old contents or the new
  ones, never a half-written file: write a sibling temp file, then rename it over
  the target. The rename is the only step that touches `file`, and on a POSIX
  filesystem it is atomic.

  This matters because the two settings files here — roots.edn, watch.edn — are
  written on one path and read on another (the watch sweep reads roots.edn on its
  own thread; the server reads watch.edn at boot). A plain `spit` truncates first,
  so a crash, a full disk, or an unlucky read in between leaves — or sees — an
  empty or truncated file, which both readers degrade to \"no roots\" / \"watch
  off\": silently forgetting what the user asked for.

  The temp file is a sibling so the rename stays within one filesystem. ATOMIC_MOVE
  is requested and, if the filesystem can't honour it, retried as a plain replacing
  move — still far better than a truncate-in-place, and the fallback only applies
  where atomicity was never available anyway.

  `file` is always one of this namespace's own paths — the two callers pass
  roots-file and watch-file. The locals are named otzar-state-* to say so, since
  rag.fs-safety-test reads these lines and has no other way to see that the target
  came from here."
  [^File file ^String content]
  (let [otzar-state-file (.getAbsoluteFile file)
        otzar-state-tmp  (File/createTempFile (str "." (.getName otzar-state-file) ".") ".tmp"
                                              (.getParentFile otzar-state-file))
        target           (.toPath otzar-state-file)]
    (try
      (spit otzar-state-tmp content)
      (try
        (Files/move (.toPath otzar-state-tmp) target
                    (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                            StandardCopyOption/REPLACE_EXISTING]))
        (catch java.nio.file.AtomicMoveNotSupportedException _
          (Files/move (.toPath otzar-state-tmp) target
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
      ;; No-op once the move succeeded; the cleanup that matters is the failure
      ;; path, where the temp file would otherwise be left beside the real one.
      (finally (.delete otzar-state-tmp)))
    nil))

;; ── Embeddings (plan §3.4) ──────────────────────────────────────────────────
(def ollama-url
  "Base URL of the Ollama sidecar that serves embeddings. Override with OLLAMA_URL."
  (or (System/getenv "OLLAMA_URL") "http://localhost:11434"))

(def embed-model
  "Ollama embedding model. Override with OTZAR_EMBED_MODEL."
  (or (System/getenv "OTZAR_EMBED_MODEL") "bge-m3"))

(def embed-timeout-s
  "Per-request timeout (seconds) for an Ollama embed call. Generous by default:
  on CPU the model can be slow to load and a batch of chunks can take a while.
  Override with OTZAR_EMBED_TIMEOUT."
  (or (some-> (System/getenv "OTZAR_EMBED_TIMEOUT") Long/parseLong) 300))

(def embed-batch
  "Max inputs per Ollama /api/embed request. Caps the unit of work a single
  request commits Ollama to, so a huge file can't turn into one hours-long,
  un-cancellable request; 32–64 is the measured throughput sweet spot. Override
  with OTZAR_EMBED_BATCH."
  (int (or (some-> (System/getenv "OTZAR_EMBED_BATCH") Long/parseLong) 64)))

(def nrepl-enabled?
  "Whether to start the in-server nREPL for live coding (CIDER attach). Off by
  default so a production `clojure -M:run` never loads the nREPL/CIDER stack; the
  :dev alias turns it on (-Dotzar.nrepl=true) *and* puts the deps on the
  classpath. Also honored via OTZAR_NREPL=1 for an ad-hoc dev boot."
  (boolean (or (= "true" (System/getProperty "otzar.nrepl"))
               (contains? #{"1" "true"} (System/getenv "OTZAR_NREPL")))))

(def nrepl-port
  "TCP port for the in-server nREPL (loopback only) that CIDER attaches to; 0
  picks a free port, published to .nrepl-port for auto-discovery. Override with
  OTZAR_NREPL_PORT."
  (int (or (some-> (System/getenv "OTZAR_NREPL_PORT") Long/parseLong) 0)))

(def index-concurrency
  "How many files to extract+embed concurrently during /index. Embedding is
  Ollama-bound and parallelizes well (measured ~linear scaling to 8 in-flight
  requests), while the Lucene writer stays single-threaded. Override with
  OTZAR_INDEX_CONCURRENCY.

  Note: with per-file batch parallelism (rag.embed/embed-pool sized by
  embed-concurrency below), Ollama load is bounded by embed-concurrency, not this
  — so this can be lowered to trim memory (fewer files in flight) without losing
  throughput; the batches of even a single file will still saturate the budget."
  (int (or (some-> (System/getenv "OTZAR_INDEX_CONCURRENCY") Long/parseLong) 8)))

(def embed-concurrency
  "How many Ollama embed requests may be in flight at once, shared across the
  whole index run — the global budget of concurrent embed calls. Every batch, from
  many files or from the many batches of one large file, competes for this same
  bound, so a single big file parallelizes without over-subscribing Ollama.
  Embedding scales ~linearly to ~8 in-flight requests (and needs OLLAMA_NUM_PARALLEL
  set high enough server-side to honor them); past that it thrashes. Override with
  OTZAR_EMBED_CONCURRENCY."
  (int (or (some-> (System/getenv "OTZAR_EMBED_CONCURRENCY") Long/parseLong) 8)))

(def watch-interval-s
  "Default interval, in seconds, between background watch sweeps (rag.watch) — the
  auto-reindex of the persisted roots. 30 minutes by default: a sweep over an
  unchanged tree is cheap (a stat + metadata lookup per file, no embedding, so the
  Ollama model is never loaded), so this only bounds how quickly a real change is
  picked up. Override with OTZAR_WATCH_INTERVAL. The client can set a per-user value
  (persisted to watch.edn) that takes precedence over this default."
  (or (some-> (System/getenv "OTZAR_WATCH_INTERVAL") Long/parseLong) 1800))
