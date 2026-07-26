# HTTP API contract

This is the source of truth shared by the Clojure server (`server/`) and the Go
client (`client/`). They communicate only over HTTP+JSON on `127.0.0.1` at the
port the server publishes to `~/.otzar/server.port`.

Status legend: ✅ implemented · ⬜ planned (milestone in parens).

| Method | Path            | Status | Notes                                                                                                                                  |
|--------|-----------------|--------|----------------------------------------------------------------------------------------------------------------------------------------|
| GET    | `/health`       | ✅     | `{status, uptime_s}`                                                                                                                   |
| GET    | `/stats`        | ✅     | `{num_docs, num_chunks}`                                                                                                               |
| GET    | `/paths`        | ✅     | `{count, paths}` — distinct indexed source files (`otzar list`, MCP `list_indexed_paths`)                                              |
| POST   | `/shutdown`     | ✅     | `{ok: true}`, then the process exits                                                                                                   |
| POST   | `/index`        | ✅     | `{paths, recursive}` → `{indexed, ...}`; incremental — re-run to skip unchanged files and replace changed ones                         |
| POST   | `/query`        | ✅     | `{q, k, mode, filters, fusion}` → `{hits}`; `mode` hybrid (default)/keyword/vector                                                     |
| GET    | `/document/:id` | ✅     | `id` is a `doc_id` (whole file) or a chunk `id`; 404 if unknown                                                                        |
| POST   | `/reindex`      | ⬜     | Not needed — `otzar admin reindex` re-embeds the indexed paths via `POST /index` with `force`, so no dedicated endpoint                |
| DELETE | `/index`        | ✅     | Wipe the whole index → `{removed_docs, removed_chunks}`; the supported reset after an embedding-dimension change (`otzar admin clear`) |
| POST   | `/prune`        | ✅     | `{dry_run}` → `{removed, kept}`; drop chunks for source files gone from disk                                                           |
| GET    | `/watch`        | ✅     | Background auto-reindex state → `{enabled, interval_s, indexing, last_sweep_at?, last_result?}`                                        |
| POST   | `/watch`        | ✅     | `{enabled, interval_s?}` toggle the sweep → the same status shape; persisted to `watch.edn`                                            |

## Lifecycle files (`~/.otzar/`)

- `server.port` — written by the **server** on boot (it binds an ephemeral port
  and records the actual one). Removed on clean shutdown. Its presence +
  a passing `/health` is how the client decides the server is up.
- `server.pid` — written by the **client** when it spawns the server; used to
  signal the process if `/shutdown` is unavailable.
- `server.log` — server stdout/stderr, written by the client's `start`.
- `roots.edn` — the paths `POST /index` was asked to index (`{:roots [{:path
  :recursive}]}`), written by the **server**. The Lucene index stores per-file
  paths, not roots, so this is the record of "index this tree" that the watch sweep
  re-scans for new/changed/deleted files.
- `watch.edn` — the persisted watch toggle (`{:enabled :interval-s}`), written by
  the **server** when `POST /watch` flips it and read at boot so the background
  auto-reindex survives a restart.

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
- `skipped` — files skipped by the extractor: empty, oversized, binary, or a PDF
  with no text layer (a scan — there is no OCR). A file we could read but are not
  allowed to (a password-protected PDF) is an `errors` entry, not a skip.
- `errors` — files that threw during extraction/chunking.

### DELETE /index

Wipe the entire index — every chunk of every file — in one call. No request body.
The source files on disk are untouched; only the index is dropped. Irreversible,
so the CLI (`otzar admin clear`) confirms before sending it. This is the supported
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
   "mtime": 1712345678000,
   "locator": "line", "loc_start": 42, "loc_end": 47}
]}
```

`mtime` is epoch milliseconds (a Lucene `LongPoint`); the ISO-8601 form in the
plan is deferred.

`locator` + `loc_start`/`loc_end` are the chunk's citation span — where in the
source it came from, and in what units:

| `locator` | units                 | formats           | cite as            |
|-----------|-----------------------|-------------------|--------------------|
| `line`    | 1-based source lines  | text, code, md, … | `/notes/rag.md:42-47` |
| `page`    | 1-based page numbers  | PDF               | `/papers/rag.pdf p.4` |

The span is inclusive, and `loc_start == loc_end` when the chunk sits in a single
unit. Read the unit from `locator` rather than from the file extension: the
server's extractor registry decides it, so a new format can bring a new unit.
All three fields are **omitted** (not null) for chunks indexed before locator
tracking — render the bare path in that case rather than inventing a range.

### GET /document/:id

Fetch one item by id. What `id` refers to decides the shape:

- **A chunk `id`** (`<doc_id>:<chunk_idx>`, contains a colon) → that single chunk,
  read straight from the index:

  ```json
  {"id": "a1b2:7", "doc_id": "a1b2", "path": "/notes/rag.md",
   "ext": "md", "mtime": 1712345678000, "chunk_idx": 7,
   "locator": "line", "loc_start": 42, "loc_end": 47,
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
  - For a format that has to be *extracted* rather than read — PDF — `text` is
    the extraction of the file as it stands on disk now, with pages separated by
    a form feed (`\f`). Faithful to the current file, but not its bytes.

Returns `404 {"error": "..."}` when the id isn't indexed, or (for a `doc_id`) the
file is no longer readable on disk.

### GET /watch · POST /watch

The background auto-reindex sweep (`otzar server watch`). The server periodically
re-scans the roots recorded in `roots.edn` — reconciling new, changed, and deleted
files against the index — without a manual `otzar index`. Off by default; the toggle
persists to `watch.edn` so it survives a restart.

`GET /watch` reports the current state:

```json
{"enabled": true, "interval_s": 1800, "next_sweep_in_s": 742, "indexing": false,
 "last_sweep_at": 1712345678000,
 "last_result": {"roots": 2, "present": 2, "pruned": [],
                 "indexed": 1, "chunks": 4, "unchanged": 37,
                 "skipped_files": 0, "errors": []}}
```

- `interval_s` — seconds between sweeps (default 1800; `OTZAR_WATCH_INTERVAL` or a
  per-user value set via `POST`).
- `next_sweep_in_s` — seconds until the next scheduled sweep, straight from the
  scheduler. Absent when watch is off; `0` when a sweep is due or running.
- `indexing` — whether an index run (manual or a sweep) holds the single-flight lock
  right now.
- `last_sweep_at` / `last_result` — the most recent sweep's time and summary, absent
  until the first sweep runs. `last_result` is `{"skipped": true}` when that sweep
  found a run already in progress, or `{"error": "..."}` when it threw.

`POST /watch {"enabled": true, "interval_s": 900}` arms the sweep (the first one
fires one interval later, so toggling never itself triggers embedding); `interval_s`
is optional and keeps the current value when omitted. `{"enabled": false}` disables
it. Either returns the resulting status (the `GET` shape).

A sweep is single-flighted against manual `/index` (and other sweeps): a scan of an
unchanged tree is cheap — a stat + metadata lookup per file, no embedding, so the
Ollama model is not loaded — and orphan pruning is skipped for that tick if any root
has gone missing (an unmounted volume), so a vanished root never deletes its subtree.

## Errors

Handlers return `{"error": "<message>"}` (sometimes with a `"data"` object) and a
non-200 status: `400` for bad requests (missing `q`/`paths`, unsupported `mode`),
`500` for server-side failures.

A dimension change is detected up front rather than failing deep in Lucene: when
the configured embedding model's vector dimension differs from what the index
already holds, `/query` (vector/hybrid) returns `400` with
`{"error": "...", "data": {"type": "dimension-mismatch", "indexed": N, "configured": M}}`,
and `/index` emits a terminal `{"type": "error", "error": "..."}` stream event. The
message names the fix (`otzar admin clear`, then re-index). It is a no-op on a
fresh/keyword-only index or when Ollama doesn't report the model's dimension.
