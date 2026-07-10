(ns rag.config
  "Runtime paths shared with the Go client. The contract: the server owns the
  port file (it picks a free port and writes the actual one); the client owns
  the pid file. Both live under ~/.myrag/."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

(def home-dir (System/getProperty "user.home"))
(def myrag-dir (io/file home-dir ".myrag"))
(def port-file (io/file myrag-dir "server.port"))
(def index-dir (io/file myrag-dir "index"))

(def ^:private owner-only
  "rwx------ — the owner may read/list/enter ~/.myrag; nobody else can."
  (PosixFilePermissions/fromString "rwx------"))

(defn ensure-dir!
  "-> Path
  Create ~/.myrag if absent and lock it to the owner, re-tightening on every
  boot. Everything inside (index/, *.pid, *.port, *.log) inherits the protection:
  with the parent unenterable by others, they can't reach the contents."
  []
  (.mkdirs myrag-dir)
  (Files/setPosixFilePermissions (.toPath myrag-dir) owner-only))

;; ── Embeddings (plan §3.4) ──────────────────────────────────────────────────
(def ollama-url
  "Base URL of the Ollama sidecar that serves embeddings. Override with OLLAMA_URL."
  (or (System/getenv "OLLAMA_URL") "http://localhost:11434"))

(def embed-model
  "Ollama embedding model. Override with MYRAG_EMBED_MODEL."
  (or (System/getenv "MYRAG_EMBED_MODEL") "bge-m3"))

(def embed-timeout-s
  "Per-request timeout (seconds) for an Ollama embed call. Generous by default:
  on CPU the model can be slow to load and a batch of chunks can take a while.
  Override with MYRAG_EMBED_TIMEOUT."
  (or (some-> (System/getenv "MYRAG_EMBED_TIMEOUT") Long/parseLong) 300))

(def embed-batch
  "Max inputs per Ollama /api/embed request. Caps the unit of work a single
  request commits Ollama to, so a huge file can't turn into one hours-long,
  un-cancellable request; 32–64 is the measured throughput sweet spot. Override
  with MYRAG_EMBED_BATCH."
  (int (or (some-> (System/getenv "MYRAG_EMBED_BATCH") Long/parseLong) 64)))

(def nrepl-port
  "TCP port for the in-server nREPL (loopback only) that CIDER attaches to; 0
  picks a free port, published to .nrepl-port for auto-discovery. Override with
  MYRAG_NREPL_PORT."
  (int (or (some-> (System/getenv "MYRAG_NREPL_PORT") Long/parseLong) 0)))

(def index-concurrency
  "How many files to extract+embed concurrently during /index. Embedding is
  Ollama-bound and parallelizes well (measured ~linear scaling to 8 in-flight
  requests), while the Lucene writer stays single-threaded. Override with
  MYRAG_INDEX_CONCURRENCY.

  Note: with per-file batch parallelism (rag.embed/embed-pool sized by
  embed-concurrency below), Ollama load is bounded by embed-concurrency, not this
  — so this can be lowered to trim memory (fewer files in flight) without losing
  throughput; the batches of even a single file will still saturate the budget."
  (int (or (some-> (System/getenv "MYRAG_INDEX_CONCURRENCY") Long/parseLong) 8)))

(def embed-concurrency
  "How many Ollama embed requests may be in flight at once, shared across the
  whole index run — the global budget of concurrent embed calls. Every batch, from
  many files or from the many batches of one large file, competes for this same
  bound, so a single big file parallelizes without over-subscribing Ollama.
  Embedding scales ~linearly to ~8 in-flight requests (and needs OLLAMA_NUM_PARALLEL
  set high enough server-side to honor them); past that it thrashes. Override with
  MYRAG_EMBED_CONCURRENCY."
  (int (or (some-> (System/getenv "MYRAG_EMBED_CONCURRENCY") Long/parseLong) 8)))
