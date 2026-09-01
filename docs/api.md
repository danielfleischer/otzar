# HTTP API contract

This is the source of truth shared by the Clojure server (`server/`) and the Go
client (`client/`). They communicate only over HTTP+JSON on `127.0.0.1` at the
port the server publishes to `~/.otzar/server.port`.

Status legend: ✅ implemented · ⬜ planned (milestone in parens).

| Method | Path            | Status | Notes                                                                                                                                  |
|--------|-----------------|--------|----------------------------------------------------------------------------------------------------------------------------------------|
| GET    | `/health`       | ✅     | `{status, uptime_s, version, source}` — see [Versioning](#versioning)                                                                  |
| GET    | `/stats`        | ✅     | `{num_docs, num_chunks}`                                                                                                               |
| GET    | `/paths`        | ✅     | `{count, paths}` — distinct indexed source files (`otzar list`, MCP `list_indexed_paths`)                                              |
| GET    | `/roots`        | ✅     | `{roots: [{path, recursive}]}` — the trees asked for, as recorded in `roots.edn` (`otzar index --now` posts them back to `/index`)     |
| DELETE | `/roots`        | ✅     | `{paths}` → `{removed, roots}` — stop tracking those trees (`otzar roots remove`); indexes and un-indexes nothing                      |
| POST   | `/shutdown`     | ✅     | `{ok: true}`, then the process exits                                                                                                   |
| POST   | `/index`        | ✅     | `{paths, recursive}` → `{indexed, ...}`; incremental — re-run to skip unchanged files and replace changed ones                         |
| POST   | `/query`        | ✅     | `{q, k, mode, filters, fusion}` → `{hits}`; `mode` hybrid (default)/keyword/vector                                                     |
| GET    | `/document/:id` | ✅     | `id` is a `doc_id` (whole file) or a chunk `id`; 404 if unknown                                                                        |
| POST   | `/reindex`      | ✅     | No body — re-embed every file already indexed (`otzar admin reindex`); streams progress like `/index` and records no roots             |
| DELETE | `/index`        | ✅     | `{forget_roots?}` → `{removed_docs, removed_chunks, forgotten_roots?}`; wipe the whole index — the supported reset after an embedding-dimension change (`otzar admin clear`) |
| POST   | `/prune`        | ✅     | `{dry_run}` → `{removed, kept}`; drop chunks for source files gone from disk                                                           |
| GET    | `/watch`        | ✅     | Background auto-reindex state → `{enabled, interval_s, indexing, last_sweep_at?, last_result?}`                                        |
| POST   | `/watch`        | ✅     | `{enabled, interval_s?}` toggle the sweep → the same status shape; persisted to `watch.edn`                                            |

## Versioning

There is no version negotiation: both halves ship from one release and speak
whatever this document says. `GET /health` reports the server's version only so
the mismatch can be *named*, not to gate anything.

`/health` also reports `source`: the absolute path of the jar the server was
booted from, or its source tree suffixed `(source)`. Both it and `version`
describe the process that answers, which is the point — the client can only
predict them, by resolving what it *would* boot from the current directory, and
that prediction says "checkout" from a checkout no matter which server is
actually serving. `otzar server status` prefers these, and falls back to its own
prediction (labelled as one) against a server too old to report them.

Both sides get their version from the same `git describe`, stamped at build time
— into the client by the Makefile's `-ldflags`, into the jar by `build.clj` (as
the classpath resource `otzar-version.txt`). Neither has one when built from
source, and both then say `dev`.

The case this exists for: upgrading an install replaces the jar on disk but does
not touch a server already running, so a new client drives an old server until
someone runs `otzar server restart`. The client warns on stderr when the two
differ, and skips the comparison when either side says `dev` (a checkout is
*expected* to differ from the installed half).

Compatibility beyond the JSON itself rests on three conventions, none of them
negotiated: the lifecycle files below, the JVM flags the client supplies when it
launches the jar (they mirror the `:run` alias in `server/deps.edn`), and the
2-second heartbeat the streaming `POST /index` writes while working.

## Lifecycle files (`~/.otzar/`)

- `server.port` — written by the **server** on boot (it binds an ephemeral port
  and records the actual one). Removed on clean shutdown. Its presence +
  a passing `/health` is how the client decides the server is up.
- `server.pid` — written by the **client** when it spawns the server; records the
  PID and its OS start identity so the client can verify ownership before using
  it to signal the process if `/shutdown` is unavailable.
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
GET  /health    200  {"status": "ok", "uptime_s": 12.34, "version": "v1.0.1",
                      "source": "/home/me/.local/share/otzar/otzar-server.jar"}
GET  /stats     200  {"num_docs": 42, "num_chunks": 137}
GET  /paths     200  {"count": 2, "paths": ["/notes/a.md", "/src/b.clj"]}
POST /shutdown  200  {"ok": true}
```

`/paths` lists the distinct absolute source-file paths currently indexed, sorted.
It backs the MCP `list_indexed_paths` tool ("do you have access to X?"). Like
`/stats`, it reports an empty set rather than an error before anything is indexed.

### GET /roots

```
GET /roots  200  {"roots": [{"path": "/notes", "recursive": true},
                            {"path": "/a.md", "recursive": false}]}
```

The trees `POST /index` was asked to index, read back from `roots.edn` — an
*intent* ("index ~/notes recursively"), where `/paths` is the outcome. Empty
before anything has been indexed.

It exists so the client can re-run an index over what is already known:
`otzar index --now` fetches these, prunes, and posts them back to `POST /index`,
grouped by their `recursive` flag. The background sweep (`GET /watch`) reads the
same file server-side, so the two agree on what "everything" means without a
shared route.

`POST /index` adds to this list and `DELETE /roots` removes from it; `POST
/reindex` deliberately does not write it, since rebuilding what is already indexed
is not a request to track anything new.

Paths posted to `POST /index` are validated *before* being recorded — a path that
is not on disk, or not readable, is a `400` and nothing is written. A root is
persisted, so a typo would otherwise be saved forever, index nothing, and stop the
sweep pruning: a root missing from disk reads exactly like an unmounted volume, and
the sweep refuses to treat a whole subtree as orphaned.

`roots.edn` is written through a temp file and a rename, so a reader on another
thread sees the old contents or the new ones and never a truncated file — which
`read-roots` would degrade to "no roots", silently forgetting every tree.

### DELETE /roots

Request:

```json
{"paths": ["/Users/me/old-notes"]}
```

Response:

```json
{"removed": ["/Users/me/old-notes"],
 "roots":   [{"path": "/Users/me/notes", "recursive": true}]}
```

Stop tracking those trees (`otzar roots remove`), so the watch sweep no longer
re-scans them. This forgets an *intent* and changes no content: chunks already
indexed under those paths stay searchable, and `POST /prune` is what drops the ones
whose files are gone.

`removed` names only the paths that were actually recorded, so a caller can tell
the user which arguments matched nothing. The paths are deliberately not validated
— a root worth forgetting is usually one whose directory has been deleted or
unmounted. A missing or empty `paths` is a `400`.

### POST /index

Request:

```json
{"paths": ["/abs/path/to/dir", "/abs/file.md"], "recursive": true}
```

- `paths` — absolute paths (the CLI resolves them); required, non-empty.
- `recursive` — descend into subdirectories (default `true` on the server; the
  CLI sends `false` unless `--recursive`/`-r` is given).
- `force` — re-embed every file even when the change gate says nothing changed
  (default `false`). To rebuild the *whole* index, use `POST /reindex` instead.

Each requested path is also recorded in `roots.edn`, because asking to index a
path is asking to keep it indexed — that is what the watch sweep re-scans.

Response:

```json
{"indexed": 12, "chunks": 340, "skipped": 3, "unchanged": 37, "removed": 1,
 "errors": [{"path": "/abs/broken.md", "error": "..."}]}
```

- `indexed` — files that produced at least one chunk.
- `unchanged` — files already indexed with identical content, skipped without
  re-embedding.
- `skipped` — files the extractor declined that were *never* indexed: empty,
  oversized, binary, or a PDF with no text layer (a scan — there is no OCR). A file
  we could read but are not allowed to (a password-protected PDF) is an `errors`
  entry, not a skip.
- `removed` — files that *had* been indexed and stopped yielding anything: emptied,
  overwritten with binary, grown past the size ceiling, or turned unreadable. Their
  stored chunks are deleted, since they no longer describe anything on disk and
  would otherwise stay searchable under a path whose content is gone. A file simply
  deleted from disk is not counted here — that is `POST /prune`, which owns the
  guard against an unmounted volume looking like a mass deletion.
- `errors` — files that threw during extraction/chunking.

### POST /reindex

No request body. Re-runs the full pipeline — re-extract, re-chunk, re-embed — over
every source file already in the index, replacing each file's chunks. Unlike
`POST /index` it re-embeds even when nothing changed, which is the point: it exists
for the changes the incremental gate cannot see (a new chunker, a new chunk field,
a different embedding model of the same dimension). A change in embedding
*dimension* can't be applied in place — `DELETE /index` first, then index.

The response is the same NDJSON progress stream as `POST /index`, terminated by
the same `result` event, and a disconnect cancels the run the same way.

The file list is the server's own `/paths`, so there is nothing to send and nothing
to get wrong. That is the whole reason this is a route rather than a client
composing `/paths` into `/index`: `/index` records what it is given as roots, so
posting the index's file list back through it would register every indexed file as
a single-file root. `/reindex` reads and writes the index only — `roots.edn` is
never touched.

Files deleted from disk since indexing yield nothing and land in `skipped`;
`POST /prune` is what drops them.

### DELETE /index

Wipe the entire index — every chunk of every file — in one call. The source files
on disk are untouched; only the index is dropped. Irreversible, so the CLI
(`otzar admin clear`) confirms before sending it. This is the supported reset after
switching to an embedding model with a different vector dimension, which Lucene
can't apply to a live field.

Request (optional):

```json
{"forget_roots": true}
```

Response:

```json
{"removed_docs": 42, "removed_chunks": 137, "forgotten_roots": ["/Users/me/notes"]}
```

- `removed_docs` / `removed_chunks` — the index's contents just before the wipe.
- `forget_roots` also empties `roots.edn`, which is what makes the wipe stick:
  roots are a standing instruction to keep those trees indexed, so left in place
  the next watch sweep re-indexes everything just cleared. `forgotten_roots` then
  lists the paths dropped. The clear runs first — it is the call that can be
  refused — so a `409` leaves the roots alone.

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

| `locator`   | units                     | formats                 | cite as                  |
|-------------|---------------------------|-------------------------|--------------------------|
| `line`      | 1-based source lines      | text, code, md, HTML, … | `/notes/rag.md:42-47`    |
| `page`      | 1-based page numbers      | PDF                     | `/papers/rag.pdf p.4`    |
| `paragraph` | 1-based paragraph numbers | docx                    | `/notes/report.docx ¶12` |

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
                 "skipped_files": 0, "removed_files": 0, "errors": []}}
```

- `interval_s` — seconds between sweeps (default 1800; `OTZAR_WATCH_INTERVAL` or a
  per-user value set via `POST`).
- `next_sweep_in_s` — seconds until the next scheduled sweep, straight from the
  scheduler. Absent when watch is off; `0` when a sweep is due or running.
- `indexing` — whether an index mutation (a manual index, a sweep, a prune, a clear)
  holds the single-flight lock right now.
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
non-200 status: `400` for bad requests (missing `q`/`paths`, an unsupported `mode`,
a path that isn't on disk), `409` when another index mutation is in progress, `500`
for server-side failures.

Every mutation of the index — `POST /index`, `POST /reindex`, `POST /prune`,
`DELETE /index`, and the background sweep — is serialised on one lock, so none can
observe or overwrite another's half-finished work. The two long writers (`/index`,
`/reindex`) queue behind whatever is running; their streams are bounded by silence,
not by a deadline. The short ones (`/prune`, `DELETE /index`) do not queue — their
callers carry a 10-second budget, so waiting out an hours-long index would time the
client out and *then* mutate the index behind its back. They return `409` with
`{"data": {"type": "index-busy", "operation": "clear"}}` and a message saying to
retry. A dry-run prune mutates nothing, so it always answers. The background sweep
simply skips its tick.

A dimension change is detected up front rather than failing deep in Lucene: when
the configured embedding model's vector dimension differs from what the index
already holds, `/query` (vector/hybrid) returns `400` with
`{"error": "...", "data": {"type": "dimension-mismatch", "indexed": N, "configured": M}}`,
and `/index` emits a terminal `{"type": "error", "error": "..."}` stream event. The
message names the fix (`otzar admin clear`, then re-index). It is a no-op on a
fresh/keyword-only index or when Ollama doesn't report the model's dimension.
