(ns rag.index.lucene
  "★ THE LUCENE CORE — this is your bit. ★

  Everything else in the server is already wired up and calls the six functions
  below. The HTTP handlers (rag.http) and the walk→extract→chunk pipeline
  (rag.index) never touch Lucene directly — they go through here. Fill in the
  bodies and M1 keyword search works end-to-end.

  ─────────────────────────────────────────────────────────────────────────────
  The component
  ─────────────────────────────────────────────────────────────────────────────
  `open` returns an opaque value (a map is idiomatic) that the rest of the server
  threads back into the other functions and finally into `close`. It should hold
  whatever long-lived Lucene objects you need — typically:
    - an IndexWriter (Lucene's invariant: exactly one writer per index dir), and
    - a SearcherManager for lock-free, refreshable reads.
  rag.index opens it lazily on first use and holds the single instance, so you
  don't need your own singleton.

  ─────────────────────────────────────────────────────────────────────────────
  Chunk map (input to add-chunks!) — one Lucene document per chunk
  ─────────────────────────────────────────────────────────────────────────────
    {:id        \"<doc_id>:<chunk_idx>\"  ; unique per chunk
     :doc_id    \"<sha1 of absolute path>\" ; stable per source file
     :path      \"/abs/path/to/file.md\"
     :ext       \"md\"                     ; may be nil
     :mtime     1712345678000             ; epoch millis (long)
     :chunk_idx 7                         ; int, position within the source doc
     :text      \"...chunk text...\"}

  Suggested field mapping (docs/rag-project-plan.md §4.1 — the M1 subset):
    id        StringField        stored, indexed (not analyzed)
    doc_id    StringField        stored, indexed
    path      StringField        stored, indexed
    ext       StringField        indexed (stored optional)
    mtime     LongPoint + stored (NumericDocValuesField too, for later sorting/filter)
    chunk_idx IntPoint  + stored
    text      TextField          stored, indexed  ← the only analyzed field; BM25 here
  (embedding + tags fields arrive in M3/M8 — ignore them for now.)

  ─────────────────────────────────────────────────────────────────────────────
  Hit map (output of keyword-search) — best first
  ─────────────────────────────────────────────────────────────────────────────
    {:id \"a1b2:7\" :doc_id \"a1b2\" :path \"/abs/path\" :score 12.4
     :text \"...\" :chunk_idx 7 :mtime 1712345678000}
  Keys are serialized to JSON verbatim, so keep them exactly as above.

  ─────────────────────────────────────────────────────────────────────────────
  Notes
  ─────────────────────────────────────────────────────────────────────────────
  - `stats` is called by GET /stats even when nothing has been indexed; return
    {:num_docs <distinct doc_id count> :num_chunks <total docs>}. num_chunks is
    just the live doc count; num_docs needs distinct doc_id values (a terms enum
    over the doc_id field, or track it yourself).
  - Deletion/reindex by doc_id is M5 — no need to handle updates now; assume a
    fresh or additive index.
  - Heads-up (your call, it's your lane): this project runs on a very new JDK, and
    Lucene 9.11's MMapDirectory path may complain there. If it does, either pin a
    JDK for the server or bump Lucene. See the note in ~/.myrag memory.

  Likely imports when you implement:
    [org.apache.lucene.store FSDirectory]
    [org.apache.lucene.index IndexWriter IndexWriterConfig DirectoryReader Term]
    [org.apache.lucene.document Document StringField TextField LongPoint IntPoint
                                Field$Store StoredField]
    [org.apache.lucene.analysis.standard StandardAnalyzer]
    [org.apache.lucene.search SearcherManager IndexSearcher]
    [org.apache.lucene.queryparser.classic QueryParser]")

(defn- todo [fn-name]
  (throw (UnsupportedOperationException.
          (str "rag.index.lucene/" fn-name
               " is not implemented yet — this is the Lucene core to fill in."))))

(defn open
  "Open (creating if absent) the Lucene index rooted at `dir` (a java.io.File).
  Return the component the other fns receive."
  [dir]
  (todo "open"))

(defn add-chunks!
  "Add a seq of chunk maps (shape above) to the index as one document each.
  Does not commit — rag.index calls `commit!` once per /index batch."
  [component chunks]
  (todo "add-chunks!"))

(defn commit!
  "Flush pending additions so they become searchable and durable, then refresh
  readers (e.g. SearcherManager.maybeRefresh)."
  [component]
  (todo "commit!"))

(defn keyword-search
  "BM25 search over the `text` field for query string `q`; return up to `k` hit
  maps (shape above), best first. Filters are M4 — ignore for now."
  [component q k]
  (todo "keyword-search"))

(defn stats
  "Return {:num_docs <distinct doc_id count> :num_chunks <live doc count>}."
  [component]
  (todo "stats"))

(defn close
  "Commit and release everything (writer, readers, directory). Called on server
  shutdown."
  [component]
  (todo "close"))
