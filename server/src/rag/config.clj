(ns rag.config
  "Runtime paths shared with the Go client. The contract: the server owns the
  port file (it picks a free port and writes the actual one); the client owns
  the pid file. Both live under ~/.otzer/."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

(def home-dir (System/getProperty "user.home"))
(def otzer-dir (io/file home-dir ".otzer"))
(def port-file (io/file otzer-dir "server.port"))
(def index-dir (io/file otzer-dir "index"))
(def roots-file
  "Persisted set of index roots — the paths /index was asked to index, so the
  watch sweep (rag.watch) knows what to re-scan. See rag.roots."
  (io/file otzer-dir "roots.edn"))
(def watch-file
  "Persisted watch toggle (enabled + interval), read at boot so the background
  sweep survives a server restart. See rag.watch."
  (io/file otzer-dir "watch.edn"))

(def ^:private owner-only
  "rwx------ — the owner may read/list/enter ~/.otzer; nobody else can."
  (PosixFilePermissions/fromString "rwx------"))

(defn ensure-dir!
  "-> Path
  Create ~/.otzer if absent and lock it to the owner, re-tightening on every
  boot. Everything inside (index/, *.pid, *.port, *.log) inherits the protection:
  with the parent unenterable by others, they can't reach the contents."
  []
  (.mkdirs otzer-dir)
  (Files/setPosixFilePermissions (.toPath otzer-dir) owner-only))

;; ── Embeddings (plan §3.4) ──────────────────────────────────────────────────
(def ollama-url
  "Base URL of the Ollama sidecar that serves embeddings. Override with OLLAMA_URL."
  (or (System/getenv "OLLAMA_URL") "http://localhost:11434"))

(def embed-model
  "Ollama embedding model. Override with OTZER_EMBED_MODEL."
  (or (System/getenv "OTZER_EMBED_MODEL") "bge-m3"))

(def embed-timeout-s
  "Per-request timeout (seconds) for an Ollama embed call. Generous by default:
  on CPU the model can be slow to load and a batch of chunks can take a while.
  Override with OTZER_EMBED_TIMEOUT."
  (or (some-> (System/getenv "OTZER_EMBED_TIMEOUT") Long/parseLong) 300))

(def embed-batch
  "Max inputs per Ollama /api/embed request. Caps the unit of work a single
  request commits Ollama to, so a huge file can't turn into one hours-long,
  un-cancellable request; 32–64 is the measured throughput sweet spot. Override
  with OTZER_EMBED_BATCH."
  (int (or (some-> (System/getenv "OTZER_EMBED_BATCH") Long/parseLong) 64)))

(def nrepl-enabled?
  "Whether to start the in-server nREPL for live coding (CIDER attach). Off by
  default so a production `clojure -M:run` never loads the nREPL/CIDER stack; the
  :dev alias turns it on (-Dotzer.nrepl=true) *and* puts the deps on the
  classpath. Also honored via OTZER_NREPL=1 for an ad-hoc dev boot."
  (boolean (or (= "true" (System/getProperty "otzer.nrepl"))
               (contains? #{"1" "true"} (System/getenv "OTZER_NREPL")))))

(def nrepl-port
  "TCP port for the in-server nREPL (loopback only) that CIDER attaches to; 0
  picks a free port, published to .nrepl-port for auto-discovery. Override with
  OTZER_NREPL_PORT."
  (int (or (some-> (System/getenv "OTZER_NREPL_PORT") Long/parseLong) 0)))

(def index-concurrency
  "How many files to extract+embed concurrently during /index. Embedding is
  Ollama-bound and parallelizes well (measured ~linear scaling to 8 in-flight
  requests), while the Lucene writer stays single-threaded. Override with
  OTZER_INDEX_CONCURRENCY.

  Note: with per-file batch parallelism (rag.embed/embed-pool sized by
  embed-concurrency below), Ollama load is bounded by embed-concurrency, not this
  — so this can be lowered to trim memory (fewer files in flight) without losing
  throughput; the batches of even a single file will still saturate the budget."
  (int (or (some-> (System/getenv "OTZER_INDEX_CONCURRENCY") Long/parseLong) 8)))

(def embed-concurrency
  "How many Ollama embed requests may be in flight at once, shared across the
  whole index run — the global budget of concurrent embed calls. Every batch, from
  many files or from the many batches of one large file, competes for this same
  bound, so a single big file parallelizes without over-subscribing Ollama.
  Embedding scales ~linearly to ~8 in-flight requests (and needs OLLAMA_NUM_PARALLEL
  set high enough server-side to honor them); past that it thrashes. Override with
  OTZER_EMBED_CONCURRENCY."
  (int (or (some-> (System/getenv "OTZER_EMBED_CONCURRENCY") Long/parseLong) 8)))

(def watch-interval-s
  "Default interval, in seconds, between background watch sweeps (rag.watch) — the
  auto-reindex of the persisted roots. 30 minutes by default: a sweep over an
  unchanged tree is cheap (a stat + metadata lookup per file, no embedding, so the
  Ollama model is never loaded), so this only bounds how quickly a real change is
  picked up. Override with OTZER_WATCH_INTERVAL. The client can set a per-user value
  (persisted to watch.edn) that takes precedence over this default."
  (or (some-> (System/getenv "OTZER_WATCH_INTERVAL") Long/parseLong) 1800))
