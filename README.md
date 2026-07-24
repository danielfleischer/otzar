# otzar

*otzar* (אוֹצָר) is Hebrew for **treasure** — and, fittingly, *thesaurus*.

A personal **hybrid retrieval system** — dense vector similarity + keyword (BM25)
+ metadata filters over your local files, in one query. A Clojure/Lucene server
holds the index; a Go/Cobra CLI controls its lifecycle and is the primary UX. The
same Go binary also speaks MCP (`otzar mcp`) so LLM hosts can search the index as
a tool.

See [`docs/rag-project-plan.md`](docs/rag-project-plan.md) for the full design and
[`docs/api.md`](docs/api.md) for the HTTP contract.

## Status

Working end to end: hybrid retrieval (BM25 + kNN over embeddings, fused with RRF),
Ollama-backed embeddings, metadata filters, live indexing progress, an MCP
adapter, and index maintenance (`stats`, `prune`, `clear`, `reindex`). Indexes
text, markdown, code, and PDF. Local-only, single-user, no auth — by design.

## Requirements

- A JDK (provided here by Homebrew's `clojure`, which bundles OpenJDK) and the
  Clojure CLI.
- Go ≥ 1.26.
- [Ollama](https://ollama.com), for embeddings. Pull the default model with
  `ollama pull bge-m3` (1024-dim); override with `OTZAR_EMBED_MODEL`. `bge-m3` is
  the default for its strong multilingual coverage — notably Hebrew, which most
  small English-first embedding models handle poorly.

## Build & run

```sh
make build          # compile the client -> bin/otzar
make server-deps    # one-time: pre-fetch JVM deps (optional, speeds first start)
```

The client locates the server source via `<repo>/server` (or `$OTZAR_SERVER_DIR`)
and boots the JVM on demand.

### Server lifecycle

```sh
./bin/otzar server start    # boot the server (background JVM)
./bin/otzar server status   # health + index stats
./bin/otzar server stop     # graceful shutdown
```

Most commands start the server automatically if it isn't running, so an explicit
`server start` is rarely needed.

### Index

```sh
./bin/otzar index notes.md report.pdf   # index individual files
./bin/otzar index -r ~/notes            # descend into a directory
./bin/otzar index -f ~/notes            # force re-embed even if unchanged
```

Unchanged files are skipped on re-index. On a TTY you get a live per-file chunk
progress view; add `-j/--json` for machine-readable output.

Indexable file types: plain text, markdown, and source code (read as-is), and
**PDF** (text extracted with PDFBox — soft line wraps rejoined into prose; no OCR,
so a scanned PDF with no text layer is skipped). Anything else is ignored. Formats
live in one registry, `server/src/rag/index/extractor.clj`, if you want to add one.

### Query

```sh
./bin/otzar query "vector database tradeoffs"          # hybrid (default)
./bin/otzar query -m keyword "exact phrase"            # BM25 only
./bin/otzar query -m vector "semantically similar"     # kNN only
./bin/otzar query -k 5 -e md,txt -p ~/notes/ "topic"   # top-5, filtered
```

`--mode` picks the retriever (`hybrid` | `keyword` | `vector`); `--ext`, `--path`,
and `--after` are metadata filters that apply to any mode. `-j/--json` emits raw
results including the hybrid score breakdown.

Hits print with the location to cite, in whatever unit the source actually has —
`~/notes/rag.md:42-47` for a text file, `~/papers/rag.pdf p.4` for a PDF.

### Admin (index maintenance)

```sh
./bin/otzar admin stats     # documents and chunks in the index
./bin/otzar admin prune     # drop indexed files that no longer exist on disk
./bin/otzar admin clear     # wipe the entire index
./bin/otzar admin reindex   # re-embed everything (after a chunker/model change)
```

### MCP

```sh
./bin/otzar mcp             # speak MCP over stdio (launched by an LLM host)
```

`mcp` exposes read-only tools — `search`, `get_document`, `list_indexed_paths` —
to an LLM host (Claude Desktop/Code) over stdin/stdout. It starts the backing
server automatically and leaves it running when the host disconnects.

### Shell completion

Cobra generates completion scripts for bash, zsh, fish, and PowerShell. For fish:

```sh
./bin/otzar completion fish | source                       # current shell
./bin/otzar completion fish > ~/.config/fish/completions/otzar.fish   # persist
```

Run `otzar completion --help` for the other shells.

## Layout

```
server/   Clojure server (JVM): HTTP API, Lucene index, indexing pipeline
client/   Go + Cobra CLI: lifecycle, HTTP client, MCP adapter, rendering
docs/     Shared HTTP contract and project plan
```

## License

GPL-3.0 — see [`LICENSE`](LICENSE).
