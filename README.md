# myrag

A personal **hybrid retrieval system** — dense vector similarity + keyword (BM25)
+ metadata filters over your local files, in one query. A Clojure/Lucene server
holds the index; a Go/Cobra CLI controls its lifecycle and is the primary UX. The
same Go binary also speaks MCP (`myrag mcp`) so LLM hosts can search the index as
a tool.

See [`docs/rag-project-plan.md`](docs/rag-project-plan.md) for the full design and
[`docs/api.md`](docs/api.md) for the HTTP contract.

## Status

**M0 — skeleton** (done): server boots on an ephemeral port and serves
`/health`, `/stats`, `/shutdown`; the client can `start`, `stop`, `status`.

Next: **M1** — keyword-only indexing end to end (`/index`, `/query?mode=keyword`).

## Requirements

- A JDK (provided here by Homebrew's `clojure`, which bundles OpenJDK) and the
  Clojure CLI.
- Go ≥ 1.26.
- Ollama (only needed from M3 onward, for embeddings).

## Build & run

```sh
make build          # compile the client -> bin/myrag
make server-deps    # one-time: pre-fetch JVM deps (optional, speeds first start)

./bin/myrag server start   # boot the server (background JVM)
./bin/myrag server status  # health + index stats
./bin/myrag server stop    # graceful shutdown
```

The client locates the server source via `<repo>/server` (or `$MYRAG_SERVER_DIR`).

## Layout

```
server/   Clojure server (JVM): HTTP API, Lucene index, indexing pipeline
client/   Go + Cobra CLI: lifecycle, HTTP client, MCP adapter, rendering
docs/     Shared HTTP contract and project plan
```
