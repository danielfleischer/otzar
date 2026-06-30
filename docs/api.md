# HTTP API contract

This is the source of truth shared by the Clojure server (`server/`) and the Go
client (`client/`). They communicate only over HTTP+JSON on `127.0.0.1` at the
port the server publishes to `~/.myrag/server.port`.

Status legend: ✅ implemented · ⬜ planned (milestone in parens).

| Method | Path            | Status | Notes                                  |
|--------|-----------------|--------|----------------------------------------|
| GET    | `/health`       | ✅     | `{status, uptime_s}`                    |
| GET    | `/stats`        | ✅     | `{num_docs, num_chunks}` (zeros for now)|
| POST   | `/shutdown`     | ✅     | `{ok: true}`, then the process exits    |
| POST   | `/index`        | ⬜ (M1)| `{paths, recursive}` → `{indexed, ...}` |
| POST   | `/reindex`      | ⬜ (M5)|                                         |
| DELETE | `/index`        | ⬜ (M5)|                                         |
| POST   | `/query`        | ⬜ (M1+)| `{q, k, mode, filters, fusion}`         |
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
GET /health   200  {"status": "ok", "uptime_s": 12.34}
GET /stats    200  {"num_docs": 0, "num_chunks": 0}
POST /shutdown 200 {"ok": true}
```
