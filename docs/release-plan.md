# Open-source release plan

Working checklist toward publishing `otzer`. Grouped by theme, roughly ordered
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
- [ ] **Drop `todo.org` from the repo.** Plain `git rm todo.org` (no history
      scrub — it holds no secrets, just an author name + task list). Or
      `git rm --cached` + `.gitignore` if you want to keep it as a local
      scratchpad.

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

- [ ] `CONTRIBUTING.md` (build, test, how the two toolchains fit together).
- [ ] CI workflow — build + test both `server/` (Clojure) and `client/` (Go).
- [x] README section on shell-completion install (`otzer completion fish | source`).
- [x] Decide on the name. Renamed `myrag` → **`otzer`** (אוֹצֵר, Hebrew for
      *curator*; cf. *otzar*, treasure/thesaurus). Renamed with it: the binary, the
      Go module path, the `MYRAG_*` env vars → `OTZER_*`, the `myrag.nrepl` system
      property, and the state dir `~/.myrag` → `~/.otzer`. The Clojure namespaces
      stay `rag.*` (internal and generic) and the repo checkout dir is unchanged.
      Note for existing installs: the state dir move orphans the old index — either
      `mv ~/.myrag ~/.otzer` or clear and re-index, and delete the stale
      `~/.local/bin/myrag`.

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
- [ ] PDF/HTML/docx extraction (plan M8). Currently plain-text/code/markdown only.
      (Partial: `rag.index.extractor` is now an open registry — one entry per
      format declaring its extensions, extraction fn, and size ceiling — and
      `walker` derives its accepted set from it, so adding a format is one
      namespace plus one registry entry, with no second list to keep in sync.
      Still open: per-format extraction itself, and generalizing the chunk's
      `line_start/line_end` span into a tagged locator (`line` | `page` | `para`)
      so PDFs cite pages and docx cites paragraphs. Leaning PDFBox for PDF and
      plain `java.util.zip` over `word/document.xml` for docx rather than Tika —
      the registry means neither has to be decided centrally.)
- [ ] Tags — schema reserves a `tags` field but nothing populates or filters on it.
- [ ] In-process embeddings (DJL/ONNX). Plan advertises Ollama as swappable; only
      the Ollama impl exists, so Ollama is currently a hard runtime dependency.
- [ ] File watching / incremental auto-index (deferred by design; state it).

## Confirmed clean (no action)

- No tracked file contains personal paths, emails, or secrets. `.clj-kondo`
  cache (which has hardcoded `/Users/...` paths) is gitignored, not tracked.
- Paths derive from `user.home` → `~/.otzer/`; nothing machine-specific baked in.
- Local-only / single-user / no-auth is a documented design decision, not a gap.
- `docs/api.md` matches the actual routes exactly.
