# HTTP API contract

This is the source of truth shared by the Clojure server (`server/`) and the Go
client (`client/`). They communicate only over HTTP+JSON on `127.0.0.1` at the
port the server publishes to `~/.myrag/server.port`.

Status legend: ✅ implemented · ⬜ planned (milestone in parens).

| Method | Path            | Status | Notes                                                                                                                                  |
|--------|-----------------|--------|----------------------------------------------------------------------------------------------------------------------------------------|
| GET    | `/health`       | ✅     | `{status, uptime_s}`                                                                                                                   |
| GET    | `/stats`        | ✅     | `{num_docs, num_chunks}`                                                                                                               |
| GET    | `/paths`        | ✅     | `{count, paths}` — distinct indexed source files (MCP `list_indexed_paths`)                                                            |
| POST   | `/shutdown`     | ✅     | `{ok: true}`, then the process exits                                                                                                   |
| POST   | `/index`        | ✅     | `{paths, recursive}` → `{indexed, ...}`; incremental — re-run to skip unchanged files and replace changed ones                         |
| POST   | `/query`        | ✅     | `{q, k, mode, filters, fusion}` → `{hits}`; `mode` hybrid (default)/keyword/vector                                                     |
| GET    | `/document/:id` | ✅     | `id` is a `doc_id` (whole file) or a chunk `id`; 404 if unknown                                                                        |
| POST   | `/reindex`      | ⬜     | Not needed — `myrag admin reindex` re-embeds the indexed paths via `POST /index` with `force`, so no dedicated endpoint                |
| DELETE | `/index`        | ✅     | Wipe the whole index → `{removed_docs, removed_chunks}`; the supported reset after an embedding-dimension change (`myrag admin clear`) |
| POST   | `/prune`        | ✅     | `{dry_run}` → `{removed, kept}`; drop chunks for source files gone from disk                                                           |

## Lifecycle files (`~/.myrag/`)

- `server.port` — written by the **server** on boot (it binds an ephemeral port
  and records the actual one). Removed on clean shutdown. Its presence +
  a passing `/health` is how the client decides the server is up.
- `server.pid` — written by the **client** when it spawns the server; used to
  signal the process if `/shutdown` is unavailable.
- `server.log` — server stdout/stderr, written by the client's `start`.

## Implemented response shapes

```
GET  /health    200  {"status": "ok", "uptime_s": 12.34}
GET  /stats     200  {"num_docs": 42, "num_chunks": 137}
GET  /paths     200  {"count": 2, "paths": ["/notes/a.md", "/src/b.clj"]}
POST /shutdown  200  {"ok": true}
```

`/paths` lists the distinct absolute source-file paths currently indexed, sorted.
It backs the MCP `list_indexed_paths` tool ("do you have access to X?"). Like
`/stats`, it reports an empty set rather than an error before anything is indexed.

### POST /index

Request:

```json
{"paths": ["/abs/path/to/dir", "/abs/file.md"], "recursive": true}
```

- `paths` — absolute paths (the CLI resolves them); required, non-empty.
- `recursive` — descend into subdirectories (default `true` on the server; the
  CLI sends `false` unless `--recursive`/`-r` is given).

Response:

```json
{"indexed": 12, "chunks": 340, "skipped": 3,
 "errors": [{"path": "/abs/broken.md", "error": "..."}]}
```

- `indexed` — files that produced at least one chunk.
- `skipped` — files skipped by the extractor (empty, oversized, or binary).
- `errors` — files that threw during extraction/chunking.

### DELETE /index

Wipe the entire index — every chunk of every file — in one call. No request body.
The source files on disk are untouched; only the index is dropped. Irreversible,
so the CLI (`myrag admin clear`) confirms before sending it. This is the supported
reset after switching to an embedding model with a different vector dimension,
which Lucene can't apply to a live field.

Response:

```json
{"removed_docs": 42, "removed_chunks": 137}
```

- `removed_docs` / `removed_chunks` — the index's contents just before the wipe.

### POST /query

Request:

```json
{"q": "how does the chunker pack sentences?",
 "k": 10,
 "mode": "hybrid",
 "filters": {"ext": ["md", "txt"],
             "path_prefix": "/notes/",
             "mtime_after": "2025-01-01"},
 "fusion": {"k_each": 50}}
```

- `q` — query string; required, non-blank.
- `k` — max hits (default 10).
- `mode` — `hybrid` (RRF fusion of both, the default), `keyword` (BM25), or
  `vector` (kNN over embeddings). Unknown modes return 400.
- `filters` — optional metadata pre-filter, AND'd, applied to every mode:
  - `ext` — string or array of file extensions; a chunk matches if its
    extension is any of them.
  - `path_prefix` — chunks whose absolute `path` starts with this string.
  - `mtime_after` — chunks modified on/after this bound (inclusive); epoch
    millis, or an ISO-8601 date (`2025-01-01`, start-of-day UTC) or instant
    (`2025-01-01T00:00:00Z`).
- `fusion` — `hybrid` only. `k_each` is the number of candidates drawn from each
  retriever before fusion (default 50).

Response:

```json
{"hits": [
  {"id": "a1b2:7", "doc_id": "a1b2", "path": "/notes/rag.md",
   "score": 12.4, "text": "...chunk text...", "chunk_idx": 7,
   "mtime": 1712345678000}
]}
```

`mtime` is epoch milliseconds (a Lucene `LongPoint`); the ISO-8601 form in the
plan is deferred.

### GET /document/:id

Fetch one item by id. What `id` refers to decides the shape:

- **A chunk `id`** (`<doc_id>:<chunk_idx>`, contains a colon) → that single chunk,
  read straight from the index:

  ```json
  {"id": "a1b2:7", "doc_id": "a1b2", "path": "/notes/rag.md",
   "ext": "md", "mtime": 1712345678000, "chunk_idx": 7,
   "text": "...stored chunk text..."}
  ```

- **A bare `doc_id`** (whole file) → the file re-read fresh **from disk** at its
  indexed `path`, so `text` is the faithful source (original whitespace, newlines,
  line numbers) — *not* the space-joined, blank-line-stripped chunk text:

  ```json
  {"doc_id": "a1b2", "path": "/notes/rag.md", "ext": "md",
   "mtime": 1712345678000, "num_chunks": 3,
   "text": "...full file contents...", "stale": true}
  ```

  - `stale` — present and `true` only when the file's current sha1 differs from
    what was indexed (it changed since the last index). Absent when they match.

Returns `404 {"error": "..."}` when the id isn't indexed, or (for a `doc_id`) the
file is no longer readable on disk.

## Errors

Handlers return `{"error": "<message>"}` (sometimes with a `"data"` object) and a
non-200 status: `400` for bad requests (missing `q`/`paths`, unsupported `mode`),
`500` for server-side failures.
