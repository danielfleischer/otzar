# otzar — design

A personal hybrid (vector + keyword + metadata) retrieval system over local
files: a Clojure server holds a Lucene index, a Go CLI controls its lifecycle and
is the primary UX, and JSON over HTTP is the only thing between them. The same Go
binary also ships an MCP server (`otzar mcp`) so any LLM host (Claude Desktop,
Claude Code, Cursor, …) can search the index as a tool.

This document describes the design and the reasoning behind it, including the
alternatives that were rejected — usually the part that makes a choice legible.
`docs/api.md` is the wire contract; where the two disagree, `api.md` wins.

## 1. Goals & non-goals

**Goals**
- Index local files and folders (text, markdown, code, HTML, PDF, docx).
- Support hybrid retrieval: dense vector similarity + keyword match + metadata
  filters (path, extension, mtime) in a single query.
- Single-user, runs locally, no auth, no multi-tenancy.
- Client controls server lifecycle (start, stop, status) and is the primary UX.
- Results returned as JSON.
- LLM hosts can use the index as a tool via an MCP server bundled into the same
  Go binary (`otzar mcp`).

**Non-goals**
- Distributed deployment, replication, sharding.
- Web UI / admin dashboard.
- Real-time file-system watching. Reindexing is not manual — `otzar server
  watch` sweeps the indexed roots on an interval inside the server — but there is
  no inotify/fswatch integration (§12).
- Reranking / cross-encoders / query rewriting.
- Multi-modal (images, audio).

## 2. High-level architecture

```
┌──────────────┐    HTTP+JSON    ┌────────────────────────────┐
│  Go CLI      │◄───────────────►│  Clojure server (JVM)      │
│  (Cobra)     │                 │  ┌──────────────────────┐  │
│              │                 │  │ HTTP API (Ring)      │  │
│  - start/stop│                 │  ├──────────────────────┤  │
│  - index     │                 │  │ Indexer pipeline     │  │
│  - query     │                 │  │  ├ walker            │  │
│  - status    │                 │  │  ├ extractor         │  │
│  - completion│                 │  │  ├ sentence segmenter│  │
│  - mcp       │                 │  │  ├ chunker           │  │
└──────────────┘                 │  │  └ embedder client   │──┼──► Ollama
        ▲│                       │  ├──────────────────────┤  │   (sidecar,
        ││ start/stop process    │  │ Lucene index         │  │    HTTP)
   stdio││                       │  │  (HNSW + inverted)   │  │
  (MCP) ││                       │  └──────────────────────┘  │
        ▼▼                       └────────────────────────────┘
┌──────────────┐
│ LLM host     │  (Claude Desktop / Code, Cursor, …)
│ launches     │
│ `otzar mcp`  │
└──────────────┘
```

Three processes in normal CLI usage; when an LLM host is in the loop it launches a
short-lived `otzar mcp` process (same binary, different subcommand) over stdio that
proxies MCP tool calls to the running Clojure server.

## 3. Component choices & rationale

### 3.1 Index engine — Apache Lucene (10.x)

- Native HNSW vector field (`KnnFloatVectorField`) since 9.0; quality and recall are
  good enough for personal-scale corpora.
- Same segment file holds inverted index, doc values for filters, and the vector
  graph — true hybrid in one library, in-process, no extra service.
- Mature, stable, JVM-native, no FFI.
- Solr/Elasticsearch/OpenSearch are wrappers around Lucene; what this needs is the
  library, not the wrappers.

Alternatives considered: Qdrant / Weaviate / Chroma — all require a separate process
and give no real keyword index. Faiss — vectors only, no metadata, no keyword.
Vespa — overkill.

### 3.2 Server language — Clojure on JVM

- Direct Java interop with Lucene, zero glue.
- REPL-driven development, good for the iterative nature of search tuning.
- Long-lived process amortizes JVM startup cost.

### 3.3 Client — Go + Cobra

- **Free shell completions.** `otzar completion {bash|zsh|fish|powershell}` is
  generated from the same subcommand/flag definitions that drive dispatch — adding a
  flag automatically extends completions.
- **Single static binary**, ~5–10 MB. No runtime to install. Drop in `~/bin`.
- **Fast cold start (~5–20 ms).** Matters for a CLI invoked many times a day,
  potentially bound to editor commands or shell hotkeys.
- Process lifecycle (`os/exec`, signals, PID files, `context.Context` for timeouts)
  and HTTP+JSON are all in the stdlib.
- The boundary to the server is HTTP+JSON, so language mismatch doesn't cost
  anything — the only thing to keep in sync is the API schema.

A TUI, should one ever happen, can use `bubbletea` in the same ecosystem; a GUI
would be a separate front-end against the same HTTP API.

Alternatives considered: **Babashka** (great Clojure ergonomics and ~30 ms startup,
but no built-in completion generator — you either hand-write per-shell scripts or
write a generator yourself); **Rust + clap** (equivalent capability, slower compile
loop, more ceremony); **Python + Typer** (most ergonomic to write but needs a Python
runtime or PyInstaller bundle).

### 3.4 Embeddings — Ollama (sidecar)

- Avoids JVM ML plumbing (DJL/ONNX, tokenizer setup, Metal/CUDA wiring).
- `POST /api/embed` (`{model, input}` → `{embeddings: [[...]]}`) is dead simple,
  and takes a batch of inputs in one call.
- The embedder boundary in the server is a single namespace (`rag.embed`) —
  swapping to in-process DJL or ONNX later is a one-day refactor, not an
  architectural redo. Until that exists, Ollama is a hard runtime dependency.
- Default model: `bge-m3` (1024-dim, runs on CPU). Chosen for its strong
  multilingual coverage — in particular it embeds Hebrew well, which most small
  English-first embedding models do not. Override via the `OTZAR_EMBED_MODEL` env
  var; the Ollama URL via `OLLAMA_URL`. Batch size, request timeout, and
  concurrency are `OTZAR_EMBED_*` knobs (see `rag.config`).

Alternatives kept in mind:
- **DJL** with `sentence-transformers/all-MiniLM-L6-v2` — in-process, no sidecar; pick
  if Ollama latency or the extra process becomes annoying.
- **ONNX Runtime for Java** — leanest, requires a one-time `optimum-cli` ONNX export
  and ~30 lines of mean-pooling/normalization code.
- **text-embeddings-inference** (HF) — faster batching/GPU than Ollama; consider if
  bulk indexing throughput matters.

### 3.5 Sentence segmentation — JVM BreakIterator (UAX #29)

- `java.text.BreakIterator/getSentenceInstance` — built into the JDK, zero deps,
  no model file to ship or download.
- Language-independent Unicode sentence rules: handles Hebrew, English, and
  mixed-script text without a per-language model. **Hebrew support is the deciding
  factor** — OpenNLP ships no Hebrew model.
- Line breaks are hard sentence boundaries: the text is split on every newline
  first, then BreakIterator splits each line on punctuation. Without that,
  line-oriented text with no periods (checklists, bullet lists, notes) collapses
  into one giant "sentence", and the packer has no size cap to catch it.
- Trade-off: rule-based breaking over-splits English period-abbreviations ("Dr.",
  initials). Harmless here — the chunker re-joins consecutive sentences, so the
  chunk *text* is unaffected; only chunk boundaries shift slightly. Hebrew is
  untouched by it, abbreviating with geresh/gershayim (׳ ״) rather than periods.

Rejected: OpenNLP (no Hebrew model, needs a downloaded model file); ICU4J
`BreakIterator` (identical behavior, +13 MB dependency); a Python sidecar
(spaCy/Stanza) — best quality and Hebrew, but adds a process to manage. Revisit
the sidecar only if standalone-sentence quality (not chunking) ever matters.

### 3.6 Chunking strategy

- Pipeline: `document → sentences (BreakIterator) → chunks (windowed packer) →
  embeddings`.
- Size-free windowing: slide over the sentences in windows of **6** sentences and
  pad each window with **2** sentences of context on each side, so a chunk holds up
  to 10 sentences and neighbours overlap. No token counting — simpler than a token
  budget; the tunables live in `rag.index.chunker/default-opts`.
- Each chunk carries metadata: source path, mtime, chunk index, extension, and its
  citation span (§13.7). The chunk is the unit of retrieval; the document is
  reconstructed from chunk metadata.

## 4. Data model

### 4.1 Lucene document fields (per chunk)

| Field          | Type                                      | Purpose                                |
|----------------|-------------------------------------------|----------------------------------------|
| `id`           | `StringField` (stored, indexed)           | `<doc_id>:<chunk_idx>`                 |
| `doc_id`       | `StringField` (stored, indexed)           | Per-source-file ID (sha1 of the path)  |
| `path`         | `StringField` (stored, indexed)           | Absolute path                          |
| `ext`          | `StringField` (stored, indexed)           | File extension, for filtering          |
| `mtime`        | `LongPoint` + stored + `NumericDocValues` | Last modified, for filtering/sort      |
| `chunk_idx`    | `IntPoint` + stored                       | Position in source doc                 |
| `locator`      | `StoredField`                             | Unit of the span below (§13.7)         |
| `loc_start`    | `StoredField`                             | First line/page/paragraph of the chunk |
| `loc_end`      | `StoredField`                             | Last line/page/paragraph               |
| `text`         | `TextField` (stored, indexed)             | Chunk text (BM25 keyword search)       |
| `embedding`    | `KnnFloatVectorField` (cosine)            | Dense vector (1024-dim for `bge-m3`)   |
| `content_sha1` | `StoredField`                             | sha1 of the extracted text (§4.3)      |
| `model_spec`   | `StoredField`                             | Embedding-model identity, `name@digest/dim` (§4.3) |
| `tags`         | —                                         | Reserved; nothing writes or filters on it yet |

`ext`, `locator`, `loc_start`/`loc_end`, `embedding`, `content_sha1`, and
`model_spec` are written only when present, and read back as *absent* rather than
null — a keyword-only chunk has no vector, and a chunk indexed before locator
tracking has no citation span.

### 4.2 Document store

- Source file content is **not** stored in Lucene beyond the chunk text — keeps the
  index small and avoids duplicate storage. `GET /document/<doc_id>` re-reads the
  file from disk instead.
- There is no side database. The per-source metadata a reindex needs — path, mtime,
  `content_sha1`, `model_spec` — rides along as stored fields on that file's
  chunks, so the index is self-describing and there is nothing to keep in sync with
  it.
- Two small EDN files under `~/.otzar/` hold what Lucene has no place for:
  `roots.edn` (the trees `POST /index` was asked to index, which is what the watch
  sweep re-scans — the index itself knows only per-file paths) and `watch.edn` (the
  persisted watch toggle).

### 4.3 Reindex semantics

- File identity is `doc_id` = sha1 of the absolute path. Re-indexing a file
  atomically *replaces* that `doc_id`'s chunks (`updateDocuments`), so chunks never
  stack up on their predecessors.
- Change detection is two-stage: an unchanged mtime skips the file outright, and a
  bumped mtime still compares `content_sha1` against the indexed text before
  re-embedding, so touching a file costs an extraction, not a model run.
- A chunk's `model_spec` records the embedding model's identity and dimension, so a
  model change invalidates its vectors instead of reading as unchanged.
- A *dimension* change is a different matter: Lucene cannot apply it to a live
  field, so it is detected up front and reported with the exact fix (`otzar admin
  clear`, then re-index) rather than failing deep inside a merge.
- Changing an *extractor*, a chunker, or the chunk schema is the blind spot in all
  of this: the bytes and the model are the same, so the gate sees no change.
  `otzar index --force <paths>` re-extracts those paths; `otzar admin reindex`
  (`POST /reindex`) does it for the whole index, taking its file list from the
  index itself and leaving the recorded roots alone (§5).

## 5. HTTP API

JSON in, JSON out, on `127.0.0.1` at the port the server publishes to
`~/.otzar/server.port`. `docs/api.md` is the source of truth for request and
response shapes; the table below is the map.

| Method | Path            | Purpose                                                |
|--------|-----------------|--------------------------------------------------------|
| GET    | `/health`       | `{status, uptime_s, version, source}` — also the client's liveness probe |
| GET    | `/stats`        | `{num_docs, num_chunks}`                                |
| GET    | `/paths`        | `{count, paths}` — the distinct source files indexed    |
| GET    | `/roots`        | `{roots}` — the trees asked for, with their recursive flag |
| POST   | `/index`        | Index paths (incremental; streams progress as NDJSON)   |
| POST   | `/reindex`      | Re-embed everything already indexed (same NDJSON stream) |
| DELETE | `/index`        | Wipe the whole index                                    |
| DELETE | `/roots`        | Stop indexing a tree: forget the root, drop its files   |
| POST   | `/prune`        | Drop chunks for source files gone from disk             |
| POST   | `/query`        | `{q, k, mode, filters, fusion}` → `{hits}`              |
| GET    | `/document/:id` | One chunk (by chunk `id`) or a whole file (by `doc_id`) |
| GET    | `/watch`        | Background auto-reindex state                           |
| POST   | `/watch`        | Toggle it / set the interval                            |
| POST   | `/shutdown`     | `{ok: true}`, then the process exits                    |

Three verbs delete, and the boundary between them is *who decided*. `POST /prune`
deletes what disk says is gone. `DELETE /index` deletes everything, on request.
`DELETE /roots` is the only one that deletes files which are present and readable,
and it does so because the user named them — which is why it is also the only one
the client confirms file-by-count before sending.

For two months there was no third verb, and the gap was invisible because
`roots remove` only ever edited `roots.edn`: un-indexing a tree left its files in
the index, reachable by search, invisible to the sweep, and counted by `otzar
list` forever. A limitation had been written down as a boundary. The rule it cost
us: when a verb removes an intent, ask what happens to the content that intent
created, and answer it in the same verb.

The verb is `otzar unindex`, not `roots remove`, for the same reason. It was
briefly the latter, and the case that needed it most read as nonsense: the
stranded tree was no longer a root, so "remove this root" named something that
didn't exist. A root is a side effect of `index`, so its removal is a side effect
of `unindex`; `otzar roots` is left as a read-only view.

`otzar index --now` is composed in the client rather than given a route of its own:
it fetches `/roots`, prunes, and posts the trees back to `POST /index`. Re-scanning
what you asked to index *is* an index request, so the composition says something
true.

`otzar admin reindex` was built the same way at first — post `/paths` back into
`/index` with `force` — and that was a mistake worth recording. `POST /index` also
records what it is given in `roots.edn`, since asking to index a path is asking to
keep it indexed. Feeding the index's own file list through it therefore promoted
every indexed file to a single-file root: the sweep then walked each file twice
(once via its tree, once as itself), and auto-prune, which requires every root to
still exist on disk, was one deleted file away from switching off for good. The
composition was wrong because the two verbs mean different things — one adds to
what is tracked, the other rebuilds what is already there. `POST /reindex` is that
second meaning, and it reads and writes the index only.

### 5.1 Query body

```json
{
  "q": "how does the chunker handle code blocks?",
  "k": 10,
  "mode": "hybrid",            // "vector" | "keyword" | "hybrid"
  "filters": {
    "ext": ["md", "txt"],
    "path_prefix": "/notes/",
    "mtime_after": "2025-01-01"
  },
  "fusion": {                  // hybrid only
    "k_each": 50               // candidates from each retriever
  }
}
```

Filters are AND'd and apply to every mode, as a pre-filter rather than a
post-filter.

### 5.2 Hit shape

```json
{
  "hits": [
    {
      "id": "a1b2:7",
      "doc_id": "a1b2",
      "path": "/notes/rag.md",
      "score": 0.0312,
      "score_breakdown": {"vector": 0.78, "keyword": 12.4},
      "text": "...chunk text...",
      "chunk_idx": 7,
      "mtime": 1712345678000,
      "locator": "line",
      "loc_start": 42,
      "loc_end": 47
    }
  ]
}
```

`mtime` is epoch milliseconds (a Lucene `LongPoint`). `score` is the fused RRF
score in `hybrid` mode and the retriever's own score otherwise; `score_breakdown`
reports each retriever's contribution, `null` where a hit came from only one of
them.

## 6. Hybrid retrieval — fusion strategy

**Reciprocal Rank Fusion (RRF)**:

1. Run vector kNN search over `embedding` with `k_each` candidates, applying metadata
   filters as a pre-filter (Lucene supports filtered kNN since 9.x).
2. Run BM25 keyword search over `text` with the same filters and `k_each`.
3. Fuse: `score(d) = Σ_i w_i / (rrf_k + rank_i(d))` over the two rankings, with
   0-based ranks, `rrf_k` = 40, and a weight of 1.0 on the keyword ranking against
   **1.3** on the vector one.
4. Take top `k`.

RRF is parameter-light and needs no score normalization — BM25 scores and cosine
similarities are not on a comparable scale, and ranks sidestep the question. The
two constants at the top of `rag.query.hybrid` are the tuning surface: `rrf_k`
damps how much the head of each list dominates (the canonical value is 60; 40 is a
sharper preference for the top ranks), and the vector weight is a deliberate tilt
toward semantic matches. Lucene 9.10+ can also combine `KnnFloatVectorQuery` with a
`BooleanQuery` for score-based fusion, which is the fallback if RRF results ever
feel off.

## 7. Client (Go + Cobra) — commands

```
otzar server start                   # boot the server (background JVM process)
otzar server stop                    # POST /shutdown, then verify exit
otzar server restart                 # stop if running, then start — picks up server changes
otzar server status                  # GET /health and /stats: where the running server came from, its version vs. ours
otzar server watch status            # armed? next sweep when? last sweep how?
otzar server watch enable [--interval 30m] | disable
otzar index <path>... [-r] [-f]      # POST /index; -r recursive, -f force re-embed
otzar index --now                    # re-scan the recorded roots (GET /roots -> POST /index)
otzar list                           # GET /paths — one absolute path per line (alias: ls)
otzar roots list                     # GET /roots — the trees asked for (alias: ls)
otzar unindex <path>... [--yes] [--dry-run]   # DELETE /roots — the inverse of index: drop
                                     #   what is indexed beneath, forget any root
otzar query "..." [-k N] [-m hybrid|vector|keyword] [-e md,txt] [-p PREFIX] [--after DATE]
otzar admin stats                    # GET /stats — index size (docs, chunks)
otzar admin prune [--dry-run]        # POST /prune — drop orphaned files
otzar admin clear [--yes] [--forget-roots]   # DELETE /index — wipe the index, optionally roots.edn too
otzar admin reindex [--yes]          # POST /reindex — re-embed everything indexed
otzar completion {bash|zsh|fish|powershell}   # emit completion script
otzar mcp                            # speak MCP over stdio (launched by LLM host)
otzar --version                      # stamped from `git describe` at build time
```

- The server binds an ephemeral loopback-only port and writes it to
  `~/.otzar/server.port` on boot; `server.log` catches its output.
- The client writes the PID plus its process-start identity to `server.pid` when
  it spawns the process and verifies both before sending any fallback signal.
- **Verbs that talk to the index auto-start the server; verbs that talk to the
  server process don't.** `index`, `query`, `list`, `admin *`, and `otzar mcp` boot
  it if it isn't running, announcing that on stderr so piped stdout stays clean.
  Everything under `server` — `watch status` included — reports on the process as
  it finds it, because a `status` that started what it was asked to report on would
  be no use.
- Default render is a pretty table; `-j/--json` is the raw JSON, for scripting.
- `completion` is supplied automatically by Cobra; the user wires it into their
  shell once (`otzar completion fish | source`, etc.).
- `mcp` is the entry point an LLM host launches as a subprocess; see §13.

The client also resolves *which* server to run: `$OTZAR_SERVER_JAR`, then a source
checkout, then the packaged jar beside the binary or under a data dir. A checkout
deliberately wins over an installed jar so development is unaffected. The JVM to
run it under is probed the same way (`$OTZAR_JAVA`, `$JAVA_HOME`,
`/usr/libexec/java_home`, Homebrew, `PATH`) — by *running* each candidate, since
macOS ships a `/usr/bin/java` stub that exists and fails.

## 8. Repository layout

A single git repo with two independent toolchains side by side. Each side has its
own build/dependency file and they only communicate through the HTTP API. The
shared contract (request/response shapes) lives in `docs/api.md` and is the source
of truth for both sides.

```
otzar/
├── README.md
├── CONTRIBUTING.md
├── LICENSE                         # GPL-3.0
├── Makefile                        # wires both toolchains: build, test, lint, install
├── .github/workflows/              # ci.yml (build+test both sides), release.yml (v* tags)
├── docs/
│   ├── api.md                      # HTTP contract — shared source of truth
│   └── design.md                   # this document
├── server/                         # Clojure server, JVM
│   ├── deps.edn
│   ├── build.clj                   # tools.build uberjar -> target/otzar-server.jar
│   ├── src/rag/
│   │   ├── main.clj                # entry point, CLI args, server bootstrap
│   │   ├── http.clj                # Ring/reitit handlers, routing
│   │   ├── config.clj              # ~/.otzar paths, env-var knobs
│   │   ├── index.clj               # indexing orchestration (walk → chunk → embed → write)
│   │   ├── index/
│   │   │   ├── lucene.clj          # open/close writer & searcher, schema, search
│   │   │   ├── walker.clj          # filesystem traversal
│   │   │   ├── extractor.clj       # extraction registry, dispatched by extension
│   │   │   ├── extract/pdf.clj     # PDF text + page locators (PDFBox)
│   │   │   ├── extract/html.clj    # tag stripping that preserves line structure
│   │   │   ├── extract/docx.clj    # word/document.xml paragraphs (zip + StAX)
│   │   │   ├── segmenter.clj       # sentence detection (JDK BreakIterator) + locators
│   │   │   └── chunker.clj         # sentence packing + overlap
│   │   ├── embed.clj               # Ollama embedding client (HTTP sidecar)
│   │   ├── query/hybrid.clj        # RRF fusion of the keyword and vector rankings
│   │   ├── roots.clj               # roots.edn — the indexed trees
│   │   └── watch.clj               # interval auto-reindex sweep
│   └── test/rag/
└── client/                         # Go + Cobra CLI
    ├── go.mod
    ├── main.go                     # entry point
    └── internal/
        ├── cmd/                    # one file per subcommand
        │   ├── root.go             # cobra root command, version, completion
        │   ├── client.go           # shared "ensure the server is up, then dial it"
        │   ├── server.go           # `server` group: start, stop, restart, status, watch
        │   ├── index.go, query.go, list.go
        │   ├── admin.go            # `admin` group: stats, prune, clear, reindex
        │   ├── confirm.go          # shared destructive-op prompt
        │   └── mcp.go              # `otzar mcp` — MCP-over-stdio adapter
        ├── lifecycle/              # PID & port files, server/JVM resolution, spawn, signals
        ├── api/                    # HTTP client, request/response types
        ├── mcp/                    # MCP tool definitions & handlers
        └── render/                 # pretty-table vs --json output
```

Build:
- Server: `clojure -M:run` from source, or `clojure -T:build uber` for the
  self-contained `otzar-server.jar` a release ships.
- Client: `go build -o ../bin/otzar ./...`.
- The top-level `Makefile` wires both: `make build`, `make uber`, `make test`,
  `make lint`, `make install` / `install-dev`.

Testing follows the same boundary: 150+ tests across the Clojure server and Go
client cover filesystem safety, extraction, indexing, retrieval, process
lifecycle, HTTP client behavior, and the MCP adapter. `make test` runs both
suites; CI also compile-checks every server namespace and lints and builds the
client.

The two sides are independently versioned in spirit but ship together — a single
git tag covers both.

## 9. Key dependencies (server)

Pinned in `server/deps.edn`:

```clojure
{:deps
 {org.clojure/clojure                      {:mvn/version "1.12.5"}
  org.apache.lucene/lucene-core            {:mvn/version "10.5.0"}
  org.apache.lucene/lucene-analysis-common {:mvn/version "10.5.0"}
  org.apache.lucene/lucene-queryparser     {:mvn/version "10.5.0"}
  ring/ring-jetty-adapter                  {:mvn/version "1.15.5"}
  metosin/reitit-ring                      {:mvn/version "0.10.1"}
  metosin/jsonista                         {:mvn/version "1.0.0"}
  org.apache.pdfbox/pdfbox                 {:mvn/version "3.0.7"}
  org.slf4j/slf4j-nop                      {:mvn/version "2.0.17"}}}
```

Lucene 10.5 provides both BM25 keyword search and the HNSW `KnnFloatVectorField`.
PDFBox is four jars for the one binary format worth reading; `slf4j-nop` silences
its unactionable chatter (font quirks, recovered xrefs) so the server log stays
readable.

Notable non-deps: **no Tika** — extraction is a small registry of per-format
functions (§10) rather than a dependency tree. **No OpenNLP** — segmentation is the
JDK's `BreakIterator` (§3.5). **No HTTP-client library** — the Ollama embed calls
use the JDK's built-in `java.net.http`. On the client side, Cobra and the official
MCP Go SDK are the only non-stdlib dependencies.

## 10. Extraction — the format registry

Extraction is an open registry in `rag.index.extractor`: one map per format,
claiming a set of extensions and supplying `File -> String`, an optional size
ceiling, and the **locator unit** its text is divided into (§13.7). A file whose
extension no format claims is not indexed. Adding a format is one entry, plus one
line in `segmenter/unit-separator` if it brings a new unit.

| Format          | Extensions               | Locator     | How                                                  |
|-----------------|--------------------------|-------------|------------------------------------------------------|
| text/code/prose | `txt md org clj go py …` | `line`      | Read as-is                                            |
| HTML            | `html htm xhtml`         | `line`      | Quote-aware tag/comment/script stripping, in place    |
| PDF             | `pdf`                    | `page`      | PDFBox; pages separated by a form feed                |
| docx            | `docx`                   | `paragraph` | `word/document.xml` out of the zip via StAX, DTDs off |

Two constraints shape the extractors:

- **HTML keeps the file's line structure exactly.** Newlines inside a dropped tag
  are preserved and none are ever inserted, which is what lets HTML keep the `line`
  locator honestly — `article.html:42` is a line you can go look at. A minified page
  is therefore one enormous line: a true citation, if not a useful one.
- **A docx has no lines or pages in the file at all** — both are decided at display
  time — so the paragraph sequence is the only citable unit it really has. Empty
  paragraphs are kept as empty units, so the numbering matches what you'd count in
  Word.

Extraction unwraps rather than transcribes where the format demands it: PDF page
text is folded back into prose (soft line breaks joined, words de-hyphenated across
a break, list items kept apart), because a PDF's line breaks are typography, not
sentences.

Skips are silent and deliberate: an empty file, one over its format's size ceiling,
a binary `.doc` or an encrypted file (neither is a zip), a scanned PDF with no text
layer (there is no OCR). A file that *could* be read but refuses to be — a
password-protected PDF — is an error, not a skip.

Known gaps: HTML is decoded as UTF-8 whatever its `<meta charset>` says, so a
windows-1255 or latin-1 page lands as junk text rather than an error; `.doc`,
`.rtf`, `.epub`, and `.pptx` are unclaimed.

## 11. Operational decisions

- **One index, not per-collection indexes.** Cross-corpus isolation hasn't been
  needed; the `path_prefix` and `ext` filters cover the usual reason to want it.
- **Ollama is assumed, not managed.** The client starts the *server* but never
  Ollama — a shared daemon with its own lifecycle is not otzar's to own. The
  requirement is documented instead.
- **Concurrency.** A single Lucene `IndexWriter` (Lucene's invariant) with a
  `SearcherManager` for lock-free, refreshable reads. Index runs — manual `/index`
  and the watch sweep alike — pass through one reentrant single-flight lock: a
  manual run waits for it, a sweep that finds it taken skips that tick rather than
  doubling the writer and embedding load. Within a run, extraction and embedding fan
  out across bounded pools (`OTZAR_INDEX_CONCURRENCY`, `OTZAR_EMBED_CONCURRENCY`)
  and results drain in completion order.
- **Durability.** A long run commits at least every few seconds, so files that
  finish while a large one is still embedding survive an interrupt; each file's
  chunks are swapped in atomically regardless.
- **Local-only.** Bound to `127.0.0.1`, no auth, single user; the state directory is
  created owner-only.

## 12. Things explicitly deferred

- Reranking (cross-encoder, ColBERT-style late interaction).
- Query expansion / HyDE.
- Per-tenant or per-collection access control.
- Distributed indexing.
- Real-time file watching (inotify/fswatch) — the interval sweep (`otzar server
  watch`) covers the need without an OS-specific watcher.
- Embedding cache for unchanged chunks across model upgrades.
- Schema versioning with automatic reindex on a bump; today a schema change is a
  manual clear + reindex, with the dimension case detected up front (§4.3).
- Tags: the field is reserved (§4.1), but nothing populates or filters on it.
- In-process embeddings (§3.4).
- **Windows.** The server half is already portable — the jar runs anywhere with a
  JDK, and Ollama ships a Windows build — except for `Files/setPosixFilePermissions`
  in `rag.config`, which throws on NTFS and needs a guard. The client half doesn't
  compile at all: `GOOS=windows` fails on the four Unix primitives `lifecycle` is
  built from — `syscall.Kill` (liveness), `Flock` (the start lock), `Setpgid`
  (detaching the server), and `SIGTERM` (the stop fallback). Each has an
  `x/sys/windows` equivalent (`OpenProcess`, `LockFileEx`,
  `CREATE_NEW_PROCESS_GROUP`, `TerminateProcess`) — already an indirect dependency
  — behind a `lifecycle_unix.go`/`lifecycle_windows.go` build-tag split, call it
  ~100 lines. Then `%LOCALAPPDATA%` in `jarCandidates`, `java.exe` in
  `resolveJava`, and `.zip` release assets. Deferred for the verification rather
  than the code: what the locking guards (concurrent cold `otzar mcp` launches
  racing to boot one server) only misbehaves in practice, so a `windows-latest` CI
  job should come first.

Untested rather than deferred: the Linux release assets are cross-compiled but
have never been run, and the "no JDK anywhere" error path (§7) is only exercised
by its unit test. Both fail loudly if they fail at all.

## 13. MCP server (`otzar mcp`)

The MCP surface is bundled into the Go CLI as the `mcp` subcommand — same binary,
same config, same release. The LLM host launches it as a subprocess over stdio; the
subcommand proxies MCP tool calls into the existing HTTP API on the Clojure server.

### 13.1 Why bundled into the Go client

Among three options considered:

1. **Standalone MCP server (third process).** Rejected: duplicates the HTTP client
   and lifecycle logic the Go CLI already has. New toolchain for a hobby project,
   no benefit since everything runs on one machine.
2. **MCP inside the Clojure server.** Rejected: JVM cold-start makes the stdio
   variant awkward (the host launches a new process per session), and the HTTP/SSE
   variant has worse host compatibility. Clojure's MCP library ecosystem is also
   thinner than Go/Python/TypeScript.
3. **MCP as a `otzar mcp` subcommand of the Go client.** Chosen. The Go binary
   already has fast cold start (perfect for stdio launch), the HTTP client to talk
   to the Clojure server, and lifecycle code to start the server if it isn't
   running. The MCP layer is a ~200-line adapter, not a new component.

### 13.2 SDK

`github.com/modelcontextprotocol/go-sdk` (the official, Anthropic-maintained Go
SDK). `mark3labs/mcp-go` is a fine fallback if a rough edge appears.

### 13.3 Tools exposed

Read-only by design. Writes (indexing, deletion) stay on the human-driven CLI — an
LLM should be able to *find* things, not silently mutate the index.

| Tool                  | Maps to                       | Args                                      | Purpose |
|-----------------------|-------------------------------|-------------------------------------------|---------|
| `search`              | `POST /query`                 | `q`, `k?`, `mode?`, `filters?`            | Primary retrieval. Returns hits with paths and snippets. |
| `get_document`        | `GET /document/:id`           | `id` (`doc_id` or chunk `id`)             | Pull full chunk or document text once a hit looks promising. |
| `list_indexed_paths`  | `GET /paths`                  | —                                         | Lets the LLM answer "do you have access to X?" without guessing. |

Tool descriptions matter — the host shows them to the LLM as the basis for tool
selection. They stay precise and concrete (good: *"Hybrid keyword+vector search over
the user's local document index. Returns ranked text chunks with file paths."*;
bad: *"Search."*), and they spell out the citation units of §13.7.

### 13.4 Lifecycle behavior

When `otzar mcp` starts:

1. Read the PID/port files under `~/.otzar/`.
2. If the server isn't running or `/health` fails: start it via `lifecycle` and wait
   for readiness (with a sane timeout). Concurrent cold launches are serialized by
   the start lock, so racing hosts boot one server, not several.
3. Initialize the MCP stdio server, register tools, enter the JSON-RPC loop.

Notices go to stderr, never stdout — stdout is the JSON-RPC channel.

When the host disconnects (stdin EOF):

1. Exit cleanly.
2. **Do not** stop the Clojure server — it may be in use by the CLI or another MCP
   session. Server lifecycle stays under explicit user control via `otzar server stop`.

### 13.5 Host configuration (example)

For Claude Desktop / Claude Code:

```json
{
  "mcpServers": {
    "notes": {
      "command": "otzar",
      "args": ["mcp"]
    }
  }
}
```

That's the entire integration surface for the user. The server name is worth a
thought: it becomes the tool prefix the model sees, so naming it after the corpus
(`notes` → `mcp__notes__search`) reads like something to reach for, where `otzar`
means nothing to a model.

### 13.6 What stays out of MCP

- Write tools (`index_path`, `delete`). Add later if there's a clear use case and
  the LLM-issued-mutation risk is acceptable.
- Long-running operations (full corpus reindex). MCP tool calls should be
  bounded-latency; progress is surfaced via the CLI instead.
- Authentication / per-tool ACLs. Single-user, local — not needed.

### 13.7 Locators — where a hit came from

The MCP consumer is an LLM host. What it does with a hit is *cite or open it*, and
the form that takes is `path:line`. That usage dictates the design, both the shape
and the caveats:

- **A range, not a point.** A chunk spans several sentences, so a single "the
  line" under-specifies where the evidence is. Hits carry `{loc_start, loc_end}`,
  1-based and inclusive (the editor/human convention).
- **Tagged, because not every format has lines.** Line numbers are meaningful only
  while extracted text == file bytes. A PDF has no lines at all — but it does have
  pages, which is what a reader would cite anyway; a docx has neither, and its
  paragraph sequence is all it can offer. So the range comes with a `locator`
  naming its unit (`line` | `page` | `paragraph`), the extractor registry decides
  it per format, and the client renders `notes.md:42-47`, `paper.pdf p.4`, or
  `report.docx ¶12`. Tagging the unit beats omitting the range for formats without
  lines: it cites the thing the format actually has instead of nothing.
- **Surface it next to the path, and announce it in the tool description.** The
  host shows the `search` description to the LLM, so it spells out every unit and
  how to cite each. Absent fields (a chunk indexed before locators) mean "no
  idea" — an LLM handles a missing field fine, but will confidently cite a wrong
  one, so they are omitted rather than nulled.

Where it lives, in pipeline order:

1. **extractor** — each registry entry declares its `:locator`, and its extracted
   text marks unit boundaries with that unit's separator (`\R` for lines, `\f` for
   pages, U+2029 for paragraphs).
2. **segmenter** — splits on the locator's separator to number the units, then on
   `\R` within each unit for hard sentence boundaries, tagging every sentence with
   its unit as `:loc`.
3. **chunker** — a chunk's range is `[first-sentence-loc, last-sentence-loc]`; the
   packer already knows the window bounds and stays incurious about what a loc
   counts.
4. **lucene** — three `StoredField`s alongside `chunk_idx`, read back in `->hit`
   and `get-chunk`.
