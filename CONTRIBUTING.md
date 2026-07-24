# Contributing

`otzar` is two toolchains glued together by the `Makefile`:

- **`server/`** — Clojure/Lucene HTTP server that holds the index.
- **`client/`** — Go/Cobra CLI (`bin/otzar`) that drives the server's
  lifecycle, is the primary UX, and also speaks MCP.

The client locates the server source via `<repo>/server` (or
`$OTZAR_SERVER_DIR`) and boots the JVM on demand — you don't need the two
running separately to develop against them.

## Requirements

- A JDK (Homebrew's `clojure` formula bundles OpenJDK) and the Clojure CLI.
- Go ≥ 1.26.
- [Ollama](https://ollama.com) with the default embedding model pulled
  (`ollama pull bge-m3`) if you're exercising indexing/query end to end.

## Build

```sh
make build          # compile the client -> bin/otzar
make server-deps    # one-time: pre-fetch the server's JVM deps
```

## Test

```sh
make test           # test-client + test-server
make test-client     # go test ./... in client/
make test-server     # check-server (compile every server namespace) + clojure -M:test
```

`check-server` compiles every `rag.*` namespace without booting the server —
run it (or `make test-server`) after any server change, even if you're not
touching tests, since it's the fastest way to catch a broken require or typo.

## Style & scope

- Keep changes scoped to what you're touching — see the top-level agent
  instructions in `.agent-shell` / `docs/` for the project's own conventions
  before reorganizing code.
- Server code lives under `rag.*` namespaces regardless of the `otzar` binary
  name; don't rename namespaces to match.
- `docs/rag-project-plan.md` is the original design doc (kept for history);
  `docs/api.md` is the current, accurate HTTP contract — update `api.md` when
  you change a route.

## Before opening a PR

1. `make test` passes.
2. `docs/api.md` still matches the routes you touched (if any).
3. New functionality that's user-facing gets a README mention.
