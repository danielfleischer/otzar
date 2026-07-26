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

## Install

otzar is two files: the `otzar` client binary and `otzar-server.jar`, the whole
server packaged with its dependencies. Grab one of each from the
[latest release](../../releases/latest) — the jar is the same on every platform —
and put them where you like:

```sh
# The path of least resistance: client on PATH, jar where the client looks.
mkdir -p ~/.local/bin ~/.local/share/otzar
tar -xzf otzar_*_darwin_arm64.tar.gz -C ~/.local/bin
mv otzar-server.jar ~/.local/share/otzar/
```

The client finds the server on its own, checking in order: `$OTZAR_SERVER_JAR`,
the directory holding the `otzar` binary, `$XDG_DATA_HOME/otzar`,
`~/.local/share/otzar`, and `<prefix>/share/otzar` or `<prefix>/lib/otzar` for a
binary in `<prefix>/bin`. So dropping both files in one directory works too, as
does a `/usr/local`-style split. To put the jar somewhere else entirely, set
`OTZAR_SERVER_JAR=/path/to/otzar-server.jar`. `otzar server status` prints which
server it resolved, which is the quickest way to check an install.

### Requirements

- **A JDK, 21 or newer**, to run the server jar. otzar looks for one at
  `$OTZAR_JAVA`, then `$JAVA_HOME`, then (on macOS) `/usr/libexec/java_home` and
  Homebrew's keg-only `openjdk`, then `java` on `PATH` — probing each by running
  it, since macOS ships a `/usr/bin/java` stub that exists but fails when no JDK
  is installed. If none works you'll get a clear error; `brew install openjdk` or
  any Temurin build fixes it.
- [Ollama](https://ollama.com), for embeddings. Pull the default model with
  `ollama pull bge-m3` (1024-dim); override with `OTZAR_EMBED_MODEL`. `bge-m3` is
  the default for its strong multilingual coverage — notably Hebrew, which most
  small English-first embedding models handle poorly.

The Clojure CLI is *not* required to run a release — only to build one.

## Build from source

Needs the Clojure CLI (Homebrew's formula bundles a JDK) and Go ≥ 1.26.

```sh
make build          # compile the client -> bin/otzar
make uber           # package the server -> server/target/otzar-server.jar
make install        # both, into ~/.local/bin + ~/.local/share/otzar
make server-deps    # one-time: pre-fetch JVM deps (optional, speeds first build)
```

`make install` overrides the locations the obvious way:

```sh
make install INSTALL_DIR=/usr/local/bin DATA_DIR=/usr/local/share/otzar
```

For working *on* otzar, `make install-dev` installs a client bound to your
checkout — it runs the server from source via `clojure -M:run`, so your edits
take effect without repackaging. A checkout's `./server` also wins over any
installed jar when you run `otzar` from the repo root.

## Usage

From a checkout these are `./bin/otzar`; installed, just `otzar`.

### Server lifecycle

```sh
otzar server start    # boot the server (background JVM)
otzar server status   # health + index stats
otzar server stop     # graceful shutdown
```

Most commands start the server automatically if it isn't running, so an explicit
`server start` is rarely needed.

### Auto-reindex

```sh
otzar server watch status              # armed? and how the last sweep went
otzar server watch enable --interval 30m
otzar server watch disable
```

Off by default. Once armed, the server re-scans the trees you've indexed on an
interval and reconciles new, changed, and deleted files, so you don't have to run
`index` again by hand. Scanning an unchanged tree is cheap — a stat per file, no
embedding, so the model never loads — and the setting persists across restarts.
The first sweep runs one interval after you enable it, never immediately.

### Index

```sh
otzar index notes.md report.pdf   # index individual files
otzar index -r ~/notes            # descend into a directory
otzar index -f ~/notes            # force re-embed even if unchanged
```

Unchanged files are skipped on re-index. On a TTY you get a live per-file chunk
progress view; add `-j/--json` for machine-readable output.

Indexable file types: plain text, markdown, and source code (read as-is), and
**PDF** (text extracted with PDFBox — soft line wraps rejoined into prose; no OCR,
so a scanned PDF with no text layer is skipped). Anything else is ignored. Formats
live in one registry, `server/src/rag/index/extractor.clj`, if you want to add one.

### Query

```sh
otzar query "vector database tradeoffs"          # hybrid (default)
otzar query -m keyword "exact phrase"            # BM25 only
otzar query -m vector "semantically similar"     # kNN only
otzar query -k 5 -e md,txt -p ~/notes/ "topic"   # top-5, filtered
```

`--mode` picks the retriever (`hybrid` | `keyword` | `vector`); `--ext`, `--path`,
and `--after` are metadata filters that apply to any mode. `-j/--json` emits raw
results including the hybrid score breakdown.

Hits print with the location to cite, in whatever unit the source actually has —
`~/notes/rag.md:42-47` for a text file, `~/papers/rag.pdf p.4` for a PDF.

### Admin (index maintenance)

```sh
otzar admin stats     # documents and chunks in the index
otzar admin prune     # drop indexed files that no longer exist on disk
otzar admin clear     # wipe the entire index
otzar admin reindex   # re-embed everything (after a chunker/model change)
```

### MCP

```sh
otzar mcp             # speak MCP over stdio (launched by an LLM host)
```

`mcp` exposes read-only tools — `search`, `get_document`, `list_indexed_paths` —
to an LLM host (Claude Desktop/Code) over stdin/stdout. It starts the backing
server automatically and leaves it running when the host disconnects.

### Shell completion

Cobra generates completion scripts for bash, zsh, fish, and PowerShell. For fish:

```sh
otzar completion fish | source                       # current shell
otzar completion fish > ~/.config/fish/completions/otzar.fish   # persist
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
