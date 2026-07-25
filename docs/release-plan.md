# Open-source release plan

Working checklist toward publishing `otzar`. Grouped by theme, roughly ordered
by what blocks a public release vs. what can follow. Findings from the
pre-release review (see session notes); `docs/api.md` was found accurate and is
the source of truth — leave it as-is.

## 0. Blockers — must land before first public push

- [x] **Add a LICENSE.** GPL-3.0 — root `LICENSE` file added (GitHub-detectable).
- [x] **Rewrite README status + usage.** Currently says "M0 skeleton done, next
      M1"; reality is ~M7 (hybrid search, embeddings, MCP, prune, admin
      subcommands all done). Update the Status section and expand Build & run to
      cover `index`, `query`, `admin *`, and `mcp` — not just `server
      start/stop/status`.
- [x] **Drop `todo.org` from the repo.** Untracked via `git rm --cached` +
      `.gitignore` entry, keeping it as a local scratchpad (no secrets, just an
      author name + task list, but no reason to publish a personal TODO).

## 1. Documentation consistency

- [x] **Reconcile `rag-project-plan.md` §8 layout with the real tree.** The plan
      lists files that don't exist: `store.clj`, `embed/{protocol,ollama,djl}.clj`,
      `query/{vector,keyword}.clj`, `rm.go`, `resources/opennlp/en-sent.bin`.
      Actual tree is flatter: single `embed.clj`, `index.clj`, only
      `query/hybrid.clj`, `clear.go`/`prune.go`. Either update the layout or mark
      the doc as "original design (historical)".
- [x] **Fix segmentation references.** §3.5's layout comment still says "OpenNLP
      sentence detection"; the code uses the JDK `BreakIterator`. (The prose in
      §3.5 already explains BreakIterator — just the layout label is stale.)
- [x] **Fix the default embedding-model docs.** Plan §3.4/§4.1/§11 say the default
      is `embeddinggemma`/`nomic-embed-text` at 768-dim. The actual default in
      `config.clj` is `bge-m3` (1024-dim), and the code is internally consistent
      about that. Update the docs — and confirm which model you actually intend to
      ship as the default.

## 2. Repo hygiene for OSS

- [x] `CONTRIBUTING.md` (build, test, how the two toolchains fit together).
- [x] CI workflow — build + test both `server/` (Clojure) and `client/` (Go).
      `.github/workflows/ci.yml`, two jobs (`server`, `client`), running the
      same `make check-server`/`make test-server`/`make client`/`make
      test-client` targets used locally; verified green locally before commit.
- [x] README section on shell-completion install (`otzar completion fish | source`).
- [x] `otzar --version`. Cobra's built-in version flag, stamped at build time from
      `git describe --tags --always --dirty` via the same `-ldflags` the Makefile
      already uses for `serverDir`; a plain `go build` reports `dev`. Bug reports
      from strangers are unreadable without it, and it can't be retrofitted onto
      binaries already in the wild.
- [x] Decide on the name. Renamed `myrag` → **`otzar`** (אוֹצָר, Hebrew for
      *treasure*/*thesaurus*). Picked over *otzer* (אוֹצֵר, curator), which is
      ambiguous — depending on the vowels it also reads as *curfew* (עוֹצֵר);
      *otzar* is unambiguous. Renamed with it: the binary, the Go module path, the
      `MYRAG_*` env vars → `OTZAR_*`, the `myrag.nrepl` system property, and the
      state dir `~/.myrag` → `~/.otzar`. The Clojure namespaces stay `rag.*`
      (internal and generic) and the repo checkout dir is unchanged.
      Note for existing installs: the state dir move orphans the old index — either
      `mv ~/.otzer ~/.otzar` or clear and re-index, and delete the stale
      `~/.local/bin/otzer`.

## 3. Test coverage gaps

- [x] Server: no tests for `http`, `embed`, or top-level `index` — the
      integration seam most likely to break for contributors. (chunker, lucene,
      segmenter, walker, hybrid are covered.) Added `http_test` (routing,
      validation, error->JSON, NDJSON streaming), `embed_test` (batching, order,
      cancellation, model-spec parsing), and `index_test` (filter/mtime coercion,
      search dispatch, file->chunks change detection) — all with the Ollama/Lucene
      deps stubbed via `with-redefs`.

## 4. Functionality — post-release / roadmap

Not release blockers, but worth tracking as the public roadmap:

- [ ] Schema versioning + auto-reindex on bump (was todo.org #10). Today a schema
      change is a manual clear + reindex. (Partial: an embedding *dimension* change
      is now detected up front and fails fast with the exact clear+reindex commands
      — `rag.index/ensure-dim!`, gating `/index` and vector/hybrid `/query` — rather
      than throwing a raw Lucene error. Auto-reindex on bump is still future.)
- [ ] HTML/docx extraction (plan M8). **PDF is done** — `rag.index.extract.pdf`
      via PDFBox (4 jars, no Tika), registered like any other format. Page text is
      unwrapped back into prose (soft line breaks folded, words de-hyphenated
      across the break, list items kept apart), and pages are separated by a form
      feed so the chunk's span is a *page* range.
      The chunk's `line_start/line_end` became a tagged locator along the way:
      chunks now carry `locator` (`line` | `page`) plus `loc_start`/`loc_end`, the
      extractor registry declares the unit per format, and the client renders
      `notes.md:42-47` or `paper.pdf p.4`. Adding docx paragraphs is one registry
      entry plus one `segmenter/unit-separator` entry.
      Note: this renames wire fields, so chunks indexed before it lose their
      citation span (hits render as a bare path) until `otzar index --force`.
      Still open: HTML is indexed as raw markup, tags and all, and docx isn't
      claimed at all.
- [ ] Tags — schema reserves a `tags` field but nothing populates or filters on it.
- [ ] In-process embeddings (DJL/ONNX). Plan advertises Ollama as swappable; only
      the Ollama impl exists, so Ollama is currently a hard runtime dependency.
- [x] File watching / incremental auto-index. Shipped as `otzar server watch`: an
      interval sweep inside the server that re-scans the roots in `roots.edn` and
      reconciles new, changed, and deleted files, single-flighted against manual
      `/index` and persisted in `watch.edn`. Real-time OS watching (inotify/fswatch)
      stays deferred by design — documented as such in the plan's non-goals.

## Confirmed clean (no action)

- No tracked file contains personal paths, emails, or secrets. `.clj-kondo`
  cache (which has hardcoded `/Users/...` paths) is gitignored, not tracked.
- Paths derive from `user.home` → `~/.otzar/`; nothing machine-specific baked in.
- Local-only / single-user / no-auth is a documented design decision, not a gap.
- `docs/api.md` matches the actual routes exactly.
