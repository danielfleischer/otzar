# HTTP API contract

This is the source of truth shared by the Clojure server (`server/`) and the Go
client (`client/`). They communicate only over HTTP+JSON on `127.0.0.1` at the
port the server publishes to `~/.myrag/server.port`.

Status legend: ✅ implemented · ⬜ planned (milestone in parens).

| Method | Path            | Status | Notes                                  |
|--------|-----------------|--------|----------------------------------------|
| GET    | `/health`       | ✅     | `{status, uptime_s}`                    |
| GET    | `/stats`        | ✅     | `{num_docs, num_chunks}`                 |
| POST   | `/shutdown`     | ✅     | `{ok: true}`, then the process exits    |
| POST   | `/index`        | ✅     | `{paths, recursive}` → `{indexed, ...}` |
| POST   | `/query`        | ✅     | `{q, k, mode, filters, fusion}` → `{hits}`; `mode` hybrid (default)/keyword/vector |
| POST   | `/reindex`      | ⬜ (M5)|                                         |
| DELETE | `/index`        | ⬜ (M5)|                                         |
| GET    | `/document/:id` | ⬜ (M6)|                                         |

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
POST /shutdown  200  {"ok": true}
```

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

## Errors

Handlers return `{"error": "<message>"}` (sometimes with a `"data"` object) and a
non-200 status: `400` for bad requests (missing `q`/`paths`, unsupported `mode`),
`500` for server-side failures.
