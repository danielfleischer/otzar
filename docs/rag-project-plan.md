# RAG Client + Server — Starting Plan

A personal project: a hybrid (vector + metadata + keyword) retrieval system with a
Clojure server, a Go CLI client that controls its lifecycle, and JSON over HTTP
between them. The same Go binary also ships an MCP server (`myrag mcp`) so any LLM
host (Claude Desktop, Claude Code, Cursor, …) can search the user's local index as a
tool. This document is the starting point — decisions can change as the project
evolves, but it captures the shape we agreed on and the tradeoffs behind each choice.

## 1. Goals & non-goals

**Goals**
- Index local files and folders (text, markdown, code, eventually PDFs).
- Support hybrid retrieval: dense vector similarity + keyword match + metadata filters
  (path, extension, mtime, tags, etc.) in a single query.
- Single-user, runs locally, no auth, no multi-tenancy.
- Client controls server lifecycle (start, stop, status) and is the primary UX.
- Results returned as JSON.
- LLM hosts can use the index as a tool via an MCP server bundled into the same
  Go binary (`myrag mcp`).

**Non-goals (for v1)**
- Distributed deployment, replication, sharding.
- Web UI / admin dashboard.
- Real-time file-system watching (manual reindex is fine).
- Reranking / cross-encoders / query rewriting (can be added later).
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
│ `myrag mcp`  │
└──────────────┘
```

Three processes in normal CLI usage; when an LLM host is in the loop it launches a
short-lived `myrag mcp` process (same binary, different subcommand) over stdio that
proxies MCP tool calls to the running Clojure server.

## 3. Component choices & rationale

### 3.1 Index engine — Apache Lucene (≥ 9.x)

- Native HNSW vector field (`KnnFloatVectorField`) since 9.0; quality and recall are
  good enough for personal-scale corpora.
- Same segment file holds inverted index, doc values for filters, and the vector
  graph — true hybrid in one library, in-process, no extra service.
- Mature, stable, JVM-native, no FFI.
- Solr/Elasticsearch/OpenSearch are Lucene wrappers; we want the library, not the
  wrappers.

Alternatives considered: Qdrant / Weaviate / Chroma — all require a separate process
and don't give us a real keyword index. Faiss — vectors only, no metadata, no
keyword. Vespa — overkill.

### 3.2 Server language — Clojure on JVM

- Direct Java interop with Lucene, zero glue.
- REPL-driven development, good for the iterative nature of search tuning.
- Long-lived process amortizes JVM startup cost.

### 3.3 Client — Go + Cobra

- **Free shell completions.** `myrag completion {bash|zsh|fish|powershell}` is
  generated from the same subcommand/flag definitions that drive dispatch — adding a
  flag automatically extends completions.
- **Single static binary**, ~5–10 MB. No runtime to install. Drop in `~/bin`.
- **Fast cold start (~5–20 ms).** Matters for a CLI invoked many times a day,
  potentially bound to editor commands or shell hotkeys.
- Process lifecycle (`os/exec`, signals, PID files, `context.Context` for timeouts)
  and HTTP+JSON are all in the stdlib.
- The boundary to the server is HTTP+JSON, so language mismatch doesn't cost
  anything — the only thing to keep in sync is the API schema.

If we later want a TUI, `bubbletea` lives in the same ecosystem; a GUI would be a
separate front-end against the same HTTP API.

Alternatives considered: **Babashka** (great Clojure ergonomics and ~30 ms startup,
but no built-in completion generator — you either hand-write per-shell scripts or
write a generator yourself); **Rust + clap** (equivalent capability, slower compile
loop, more ceremony); **Python + Typer** (most ergonomic to write but needs a Python
runtime or PyInstaller bundle).

### 3.4 Embeddings — Ollama (sidecar) for v1

- Avoids JVM ML plumbing (DJL/ONNX, tokenizer setup, Metal/CUDA wiring) for v1.
- `POST /api/embed` (`{model, input}` → `{embeddings: [[...]]}`) is dead simple,
  and takes a batch of inputs in one call.
- The embedder boundary in the server is a single namespace (`rag.embed`) —
  swapping to in-process DJL or ONNX later is a one-day refactor, not an
  architectural redo.
- Default model: `embeddinggemma` (768-dim, runs on CPU). Override via the
  `MYRAG_EMBED_MODEL` env var; the Ollama URL via `OLLAMA_URL`.

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
- Trade-off: rule-based breaking over-splits English period-abbreviations ("Dr.",
  initials). Harmless here — the chunker re-joins consecutive sentences, so the
  chunk *text* is unaffected; only chunk boundaries shift slightly.

Rejected: OpenNLP (no Hebrew model, needs a downloaded model file); ICU4J
`BreakIterator` (identical behavior, +13 MB dependency); a Python sidecar
(spaCy/Stanza) — best quality and Hebrew, but adds a process to manage. Revisit
the sidecar only if standalone-sentence quality (not chunking) ever matters.

### 3.6 Chunking strategy

- Pipeline: `document → sentences (BreakIterator) → chunks (windowed packer) →
  embeddings`.
- Size-free windowing: slide over the sentences in windows of **3** sentences and
  pad each window with **1** sentence of overlap on each side, so neighbours share
  context. No token counting — simpler than a token budget and good enough for v1;
  revisit if recall suffers.
- Each chunk carries metadata: source path, mtime, chunk index, extension. Chunk is
  the unit of retrieval; the document is reconstructed from chunk metadata.

## 4. Data model

### 4.1 Lucene document fields (per chunk)

| Field          | Type                              | Purpose                          |
|----------------|-----------------------------------|----------------------------------|
| `id`           | `StringField` (stored, indexed)   | `<doc-id>:<chunk-idx>`           |
| `doc_id`       | `StringField` (stored, indexed)   | Stable per-source-file ID (sha1) |
| `path`         | `StringField` (stored, indexed)   | Absolute path                    |
| `ext`          | `StringField` (indexed)           | File extension, for filtering    |
| `mtime`        | `LongPoint` + stored              | Last modified, for filtering     |
| `chunk_idx`    | `IntPoint` + stored               | Position in source doc           |
| `text`         | `TextField` (stored, indexed)     | Chunk text (BM25 keyword search) |
| `embedding`    | `KnnFloatVectorField` (cosine)    | Dense vector (768-dim)           |
| `tags`         | `StringField` multi-valued        | User tags (future)               |

### 4.2 Document store

- Source file content is **not** stored in Lucene beyond the chunk text — keeps the
  index small and avoids duplicate storage.
- A side-table (sqlite or just a flat directory of JSON manifests) keeps per-source
  metadata: original path, sha1, indexed-at, embedding model + version (for
  reindexing on model change).

### 4.3 Reindex semantics

- File identity = `(absolute_path, sha1)`. If sha1 changes, old chunks for that
  `doc_id` are deleted and new ones inserted.
- Embedding model version is recorded; if it changes, full reindex required.

## 5. HTTP API (v1)

JSON in, JSON out. All endpoints idempotent where it makes sense.

| Method | Path             | Body / Params                             | Returns                       |
|--------|------------------|-------------------------------------------|-------------------------------|
| GET    | `/health`        | —                                         | `{status, uptime_s, ...}`     |
| GET    | `/stats`         | —                                         | `{num_docs, num_chunks, ...}` |
| POST   | `/index`         | `{paths: [...], recursive: bool, ...}`    | `{indexed, skipped, errors}`  |
| POST   | `/reindex`       | `{paths: [...]}` (optional)               | same                          |
| DELETE | `/index`         | `{paths: [...]}` or `{doc_ids: [...]}`    | `{deleted}`                   |
| POST   | `/query`         | `{q, k, filters, mode, ...}` (see below)  | `{hits: [...]}`               |
| GET    | `/document/:id`  | `id` is `doc_id` or chunk `id`            | `{id, path, text, mtime, ...}`|
| POST   | `/shutdown`      | —                                         | `{ok: true}` then exits       |

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
  "fusion": {                  // only for hybrid
    "method": "rrf",           // reciprocal rank fusion
    "k_each": 50               // candidates from each retriever
  }
}
```

### 5.2 Hit shape

```json
{
  "hits": [
    {
      "id": "a1b2...:7",
      "doc_id": "a1b2...",
      "path": "/notes/rag.md",
      "score": 0.812,
      "score_breakdown": {"vector": 0.78, "keyword": 12.4, "rrf": 0.812},
      "text": "...chunk text...",
      "chunk_idx": 7,
      "mtime": "2025-04-12T09:15:33Z"
    }
  ]
}
```

## 6. Hybrid retrieval — fusion strategy

For v1, **Reciprocal Rank Fusion (RRF)**:

1. Run vector kNN search over `embedding` with `k_each` candidates, applying metadata
   filters as a pre-filter (Lucene supports filtered kNN since 9.x).
2. Run BM25 keyword search over `text` with the same filters and `k_each`.
3. Fuse: `score(d) = Σ 1 / (rank_i(d) + 60)` over the two rankings.
4. Take top `k`.

RRF is parameter-light, doesn't require score normalization, and works well for a
v1. Lucene 9.10+ also has `KnnFloatVectorQuery` combinable with `BooleanQuery` for
score-based fusion — we can revisit if RRF results feel off.

## 7. Client (Go + Cobra) — commands

```
myrag start                          # boot the server (background JVM process)
myrag stop                           # POST /shutdown, then verify exit
myrag status                         # GET /health and /stats
myrag index <path> [--recursive]     # POST /index
myrag reindex [<path>]               # POST /reindex
myrag rm <path>                      # DELETE /index
myrag query "..." [--k N] [--ext md,txt] [--mode hybrid|vector|keyword]
myrag query "..." --json             # raw JSON output for piping
myrag completion {bash|zsh|fish|powershell}   # emit completion script
myrag mcp                            # speak MCP over stdio (launched by LLM host)
```

- `start` writes a PID file under `~/.myrag/server.pid` and a port file (server picks
  a free port and writes it). `stop`/`status`/`query` read the port file.
- Default render: pretty table; `--json` for scripting.
- `completion` is supplied automatically by Cobra; the user wires it into their
  shell once (`myrag completion fish | source`, etc.).
- `mcp` is the entry point an LLM host launches as a subprocess; see §13.

## 8. Repository layout

A single git repo with two independent toolchains side by side. Each side has its own
build/dependency file and they only communicate through the HTTP API. The shared
contract (request/response shapes) lives in `docs/api.md` at the repo root and is
the source of truth for both sides.

```
rag/
├── README.md
├── docs/
│   └── api.md                      # HTTP contract — shared source of truth
├── server/                         # Clojure server, JVM
│   ├── deps.edn
│   ├── src/rag/
│   │   ├── main.clj                # entry point, CLI args, server bootstrap
│   │   ├── http.clj                # Ring handlers, routing
│   │   ├── index/
│   │   │   ├── lucene.clj          # open/close writer & searcher, schema
│   │   │   ├── walker.clj          # filesystem traversal, mime sniffing
│   │   │   ├── extractor.clj       # text extraction per file type
│   │   │   ├── segmenter.clj       # OpenNLP sentence detection
│   │   │   ├── chunker.clj         # sentence packing + overlap
│   │   │   └── store.clj           # source manifest side-table
│   │   ├── embed/
│   │   │   ├── protocol.clj        # Embedder protocol — swap-point
│   │   │   ├── ollama.clj          # default impl (HTTP sidecar)
│   │   │   └── djl.clj             # (later) in-process impl
│   │   ├── query/
│   │   │   ├── vector.clj          # kNN with filter
│   │   │   ├── keyword.clj         # BM25 with filter
│   │   │   └── hybrid.clj          # RRF fusion
│   │   └── config.clj
│   └── resources/
│       └── opennlp/en-sent.bin
└── client/                         # Go + Cobra CLI
    ├── go.mod
    ├── go.sum
    ├── main.go                     # cobra root command, version, completion
    └── internal/
        ├── cmd/                    # one file per subcommand
        │   ├── start.go
        │   ├── stop.go
        │   ├── status.go
        │   ├── index.go
        │   ├── reindex.go
        │   ├── rm.go
        │   ├── query.go
        │   └── mcp.go              # `myrag mcp` — MCP-over-stdio adapter
        ├── lifecycle/              # PID & port files, process spawn, signals
        │   └── lifecycle.go
        ├── api/                    # HTTP client, request/response types
        │   ├── client.go
        │   └── types.go
        ├── mcp/                    # MCP tool definitions & handlers
        │   ├── server.go           # registers tools, runs JSON-RPC stdio loop
        │   └── tools.go            # search, get_document, list_indexed_paths
        └── render/                 # pretty-table vs --json output
            └── render.go
```

Build:
- Server: `cd server && clj -M:run` (or `clj -T:build uber` for a fat JAR).
- Client: `cd client && go build -o ../bin/myrag ./...`.
- Top-level `Makefile` (optional) wires both: `make build`, `make run`, `make test`.

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
  metosin/jsonista                         {:mvn/version "1.0.0"}}}
```

Notable non-deps: **no OpenNLP** — segmentation is the JDK's `BreakIterator`
(§3.5). **No HTTP-client library** — the Ollama embed calls use the JDK's
built-in `java.net.http`. Lucene 10.5 provides both BM25 keyword search and the
HNSW `KnnFloatVectorField` used for vectors.

## 10. Milestones

A rough sequence — each milestone is independently runnable and demoable.

1. **M0 — skeleton**: server boots, `/health` works; client can start/stop/status.
2. **M1 — keyword-only indexing**: walk a folder, extract text, naive whitespace
   chunking, index into Lucene `text` field, `/query` with `mode=keyword` works
   end-to-end. No embeddings yet.
3. **M2 — sentences & chunking**: replace naive chunker with a BreakIterator
   segmenter + a windowed sentence packer (3-sentence windows, 1 sentence of
   overlap each side). Reindex; verify chunk boundaries look sane on real docs.
4. **M3 — embeddings & vector search**: Ollama integration behind `Embedder`
   protocol; backfill embeddings on next index; `/query` with `mode=vector` works.
5. **M4 — hybrid + filters**: filtered kNN, filtered BM25, RRF fusion;
   `mode=hybrid` is the default. Metadata filters (`ext`, `path_prefix`,
   `mtime_after`).
6. **M5 — incremental reindex**: sha1-based change detection, delete-and-replace by
   `doc_id`, embedding-model-version invalidation.
7. **M6 — fetch-by-id**: add `GET /document/:id` to the server (needed by the MCP
   `get_document` tool and for "open the source" UX in the CLI).
8. **M7 — MCP server**: `myrag mcp` subcommand. Tools: `search`, `get_document`,
   `list_indexed_paths`. Lazily ensures the Clojure server is running via
   `lifecycle.go`. Verify end-to-end with at least one host (Claude Desktop or
   Claude Code).
9. **M8 — polish**: better extractors (PDF via Tika?), tags, query DSL niceties,
   maybe a TUI.

## 11. Open questions to resolve before / during M0

- Embedding dimension — pin to 768 (`nomic-embed-text`) or stay configurable from
  day one? Configurable is cheap if we record the dim in the manifest.
- Single index vs. per-collection indexes. Default to single; revisit if cross-corpus
  isolation matters.
- Ollama lifecycle — does the client also start Ollama, or assume the user runs it?
  Probably assume for v1; document clearly.
- File types in v1 — start with `.txt`, `.md`, source code; PDF/HTML via Tika is
  M6.
- Concurrency model — single Lucene `IndexWriter` (Lucene's invariant), pool of
  searchers refreshed via `SearcherManager`. Indexing requests serialize through a
  channel/agent.

## 12. Things explicitly deferred

- Reranking (cross-encoder, ColBERT-style late interaction).
- Query expansion / HyDE.
- Per-tenant or per-collection access control.
- Distributed indexing.
- Real-time file watching (inotify/fswatch).
- Embedding cache for unchanged chunks across model upgrades.

## 13. MCP server (`myrag mcp`)

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
3. **MCP as a `myrag mcp` subcommand of the Go client.** Chosen. The Go binary
   already has fast cold start (perfect for stdio launch), the HTTP client to talk
   to the Clojure server, and lifecycle code to start the server if it isn't
   running. The MCP layer is a ~200-line adapter, not a new component.

### 13.2 SDK

`github.com/modelcontextprotocol/go-sdk` (the official, Anthropic-maintained Go
SDK). `mark3labs/mcp-go` is a fine fallback if a rough edge appears.

### 13.3 Tools exposed (v1)

Read-only by design. Writes (indexing, deletion) stay on the human-driven CLI for
now — an LLM should be able to *find* things, not silently mutate the index.

| Tool                  | Maps to                       | Args                                      | Purpose |
|-----------------------|-------------------------------|-------------------------------------------|---------|
| `search`              | `POST /query`                 | `q`, `k?`, `mode?`, `filters?`            | Primary retrieval. Returns hits with paths and snippets. |
| `get_document`        | `GET /document/:id`           | `id` (`doc_id` or chunk `id`)             | Pull full chunk or document text once a hit looks promising. |
| `list_indexed_paths`  | `GET /stats` (extended)       | —                                         | Lets the LLM answer "do you have access to X?" without guessing. |

Tool descriptions matter — the host shows them to the LLM as the basis for tool
selection. Keep them precise and concrete (good: *"Hybrid keyword+vector search over
the user's local document index. Returns ranked text chunks with file paths."*;
bad: *"Search."*).

### 13.4 Lifecycle behavior

When `myrag mcp` starts:

1. Read PID/port file under `~/.myrag/`.
2. If server not running or `/health` fails: start it via `lifecycle.go` and wait
   for readiness (with a sane timeout).
3. Initialize the MCP stdio server, register tools, enter the JSON-RPC loop.

When the host disconnects (stdin EOF):

1. Exit cleanly.
2. **Do not** stop the Clojure server — it may be in use by the CLI or another MCP
   session. Server lifecycle stays under explicit user control via `myrag stop`.

### 13.5 Host configuration (example)

For Claude Desktop / Claude Code:

```json
{
  "mcpServers": {
    "myrag": {
      "command": "myrag",
      "args": ["mcp"]
    }
  }
}
```

That's the entire integration surface for the user.

### 13.6 What stays out of MCP for v1

- Write tools (`index_path`, `delete`). Add later if there's a clear use case and
  the LLM-issued-mutation risk is acceptable.
- Long-running operations (full corpus reindex). MCP tool calls should be
  bounded-latency; surface progress via the CLI instead.
- Authentication / per-tool ACLs. Single-user, local — not needed.

### 13.7 Source line numbers for citation (deferred — see todo.org #4)

The MCP consumer is an LLM host (Claude Code/Desktop). What it does with a hit is
*cite or open it* — and the format it natively understands is `path:line`. That
usage dictates the design, both the shape and the caveats:

- **A line range, not a point.** A chunk spans several sentences over several
  lines, so a single "the line" under-specifies where the evidence is. Return
  `{:line_start :line_end}` (1-based, editor/human convention) so the model can
  cite `path:Lstart-Lend` and `get_document` can fetch a range.
- **Surface it next to the path, and announce it in the tool description.** The
  host shows the `search` description to the LLM; if hits carry line ranges, say so
  (e.g. *"…returns ranked chunks with file path and line range"*) or the model
  won't know it may cite them.
- **Omit for formats without source lines — never fake it.** Line numbers are only
  meaningful while extracted text == file bytes, which is exactly today's plain-text
  extractor (§3.5). M8's Tika extractors (PDF/HTML) *reconstruct* text; offsets
  won't map to source lines (PDFs have none). For those, omit the field entirely —
  an LLM handles an absent field fine, but will confidently cite a wrong one.

Feasibility: **easy-ish (an afternoon).** The data is already computed and thrown
away — `segmenter/segment` holds each sentence's char offset (`start` in the
BreakIterator loop) and discards it. Line number = count of `\n` in the source
before that offset, +1 (precompute newline offsets once, binary-search per
sentence). Capture points, in pipeline order:

1. **segmenter** — return `{:text :line}` per sentence instead of a bare string.
2. **chunker** — a chunk's range is `[first-sentence-line, last-sentence-line]`; the
   packer already knows the window's `from`/`to` sentence indices. Add
   `:line_start`/`:line_end` to the emitted map.
3. **lucene** — store two `StoredField`s alongside `chunk_idx`; retrieve in `->hit`.
   Additive/backward-compatible: old docs return nil, so only a reindex is needed.

The one real cost isn't the algorithm — it's that `segment`'s return type changes
from `[String]` to `[map]`, a shared-contract change that ripples into the chunker
and into both `segmenter_test` and `chunker_test`.
