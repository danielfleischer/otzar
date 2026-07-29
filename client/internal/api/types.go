// Package api is the HTTP client and the request/response types that mirror the
// server's JSON contract (docs/api.md). Keep these structs in sync with the
// Clojure handlers in server/src/rag/http.clj.
package api

// Health is the body of GET /health.
type Health struct {
	Status  string  `json:"status"`
	UptimeS float64 `json:"uptime_s"`
}

// Stats is the body of GET /stats.
type Stats struct {
	NumDocs   int `json:"num_docs"`
	NumChunks int `json:"num_chunks"`
}

// RootsResult is the body of GET /roots: the trees asked for, not the files that
// landed in the index (that's Paths). Recursive is the flag each was indexed with.
type RootsResult struct {
	Roots []Root `json:"roots"`
}

// Root is one indexed tree — an absolute path plus whether it was descended into.
type Root struct {
	Path      string `json:"path"`
	Recursive bool   `json:"recursive"`
}

// IndexRequest is the body of POST /index.
type IndexRequest struct {
	Paths     []string `json:"paths"`
	Recursive bool     `json:"recursive"`
	Force     bool     `json:"force,omitempty"` // re-embed even unchanged files (schema/chunker change)
}

// IndexResult is the "result" event's payload, streamed as the last line of the
// NDJSON POST /index response.
type IndexResult struct {
	Indexed   int          `json:"indexed"`   // files that produced at least one chunk
	Chunks    int          `json:"chunks"`    // total chunks written
	Skipped   int          `json:"skipped"`   // files skipped (empty/binary/oversized)
	Unchanged int          `json:"unchanged"` // files already indexed with identical content
	Cancelled bool         `json:"cancelled,omitempty"`
	Errors    []IndexError `json:"errors"`
}

// IndexEvent is one line of the NDJSON stream from POST /index. Total and Done are
// overloaded by Type (each event only sets the ones it needs):
//
//	start       Total = files to index
//	file-start  Path, Total = this file's chunk count
//	chunk       Path, Done  = chunks embedded so far, Total = this file's chunks
//	file        Done = files completed, Path, Status, Chunks (written for this file)
//	result      Result = the terminal summary
//	error       Error  = the failure message
type IndexEvent struct {
	Type   string       `json:"type"`
	Total  int          `json:"total"`
	Done   int          `json:"done"`
	Path   string       `json:"path"`
	Status string       `json:"status"`
	Chunks int          `json:"chunks"`
	Result *IndexResult `json:"result"`
	Error  string       `json:"error"`
}

// IndexError reports a single file that failed to index.
type IndexError struct {
	Path  string `json:"path"`
	Error string `json:"error"`
}

// PruneRequest is the body of POST /prune. DryRun previews the orphans without
// deleting anything.
type PruneRequest struct {
	DryRun bool `json:"dry_run,omitempty"`
}

// PruneResult is the body returned by POST /prune: the orphaned source files
// dropped from the index (or, in a dry run, the ones that would be) and how many
// indexed files remain.
type PruneResult struct {
	Removed []string `json:"removed"`
	Kept    int      `json:"kept"`
	DryRun  bool     `json:"dry_run,omitempty"`
}

// ClearResult is the body returned by DELETE /index: the counts that were removed
// (the index's contents read just before the wipe).
type ClearResult struct {
	RemovedDocs   int `json:"removed_docs"`
	RemovedChunks int `json:"removed_chunks"`
}

// QueryRequest is the body of POST /query.
type QueryRequest struct {
	Q       string         `json:"q"`
	K       int            `json:"k,omitempty"`
	Mode    string         `json:"mode,omitempty"`
	Filters map[string]any `json:"filters,omitempty"`
}

// QueryResult is the body returned by POST /query.
type QueryResult struct {
	Hits []Hit `json:"hits"`
}

// Hit is one ranked chunk. MTime is epoch milliseconds (a Lucene LongPoint);
// the ISO-string form in the plan is deferred.
//
// Locator/LocStart/LocEnd are the chunk's citation span: what to point at, and in
// which units. Locator is "line" for text and code, "page" for a PDF — it is not
// derivable from the extension, since the server's extractor registry decides it.
// All three are absent (empty/0) for chunks indexed before locator tracking.
type Hit struct {
	ID             string          `json:"id"`
	DocID          string          `json:"doc_id"`
	Path           string          `json:"path"`
	Score          float64         `json:"score"`
	Text           string          `json:"text"`
	ChunkIdx       int             `json:"chunk_idx"`
	MTime          int64           `json:"mtime"`
	Locator        string          `json:"locator,omitempty"`
	LocStart       int             `json:"loc_start,omitempty"`
	LocEnd         int             `json:"loc_end,omitempty"`
	ScoreBreakdown *ScoreBreakdown `json:"score_breakdown,omitempty"`
}

// ScoreBreakdown reports the per-retriever component scores behind a hybrid hit:
// the BM25 keyword score and the vector cosine score. Each is nil when the chunk
// was absent from that retriever's results. Only hybrid queries emit it, so the
// pointer stays nil (and omitempty drops it) for keyword- and vector-only modes.
type ScoreBreakdown struct {
	Keyword *float64 `json:"keyword"`
	Vector  *float64 `json:"vector"`
}

// Document is the body returned by GET /document/:id. It covers both shapes the
// endpoint can return: a single chunk (ChunkIdx set, Text is the stored chunk) or
// a whole file (NumChunks set, Text is the source read fresh from disk). ChunkIdx
// is a pointer because chunk index 0 is valid and must survive omitempty.
type Document struct {
	ID        string `json:"id,omitempty"`
	DocID     string `json:"doc_id"`
	Path      string `json:"path"`
	Ext       string `json:"ext,omitempty"`
	MTime     int64  `json:"mtime"`
	ChunkIdx  *int   `json:"chunk_idx,omitempty"`
	Locator   string `json:"locator,omitempty"`   // chunk shape only: the citation span,
	LocStart  int    `json:"loc_start,omitempty"` // in lines (text) or pages (PDF)
	LocEnd    int    `json:"loc_end,omitempty"`
	NumChunks int    `json:"num_chunks,omitempty"`
	Text      string `json:"text"`
	Stale     bool   `json:"stale,omitempty"` // file changed on disk since indexing
}

// PathsResult is the body returned by GET /paths.
type PathsResult struct {
	Count int      `json:"count"`
	Paths []string `json:"paths"`
}

// WatchRequest is the body of POST /watch. IntervalS of 0 (omitted) tells the
// server to keep the current/default interval; it is only meaningful with Enabled.
type WatchRequest struct {
	Enabled   bool `json:"enabled"`
	IntervalS int  `json:"interval_s,omitempty"`
}

// WatchStatus is the body of GET /watch and the response to POST /watch: whether
// the background auto-reindex sweep is armed, its interval in seconds, how long
// until the next sweep, whether an index run is happening right now, and a summary
// of the last sweep (if any). NextSweepInS is only sent while the sweep is armed.
type WatchStatus struct {
	Enabled      bool         `json:"enabled"`
	IntervalS    int          `json:"interval_s"`
	NextSweepInS int          `json:"next_sweep_in_s,omitempty"`
	Indexing     bool         `json:"indexing"`
	LastSweepAt  int64        `json:"last_sweep_at,omitempty"` // epoch millis
	LastResult   *SweepResult `json:"last_result,omitempty"`
}

// SweepResult summarises one background sweep (WatchStatus.LastResult). Skipped is
// set when a run was already in progress and the sweep did nothing; Error is set
// when the sweep itself threw. Otherwise the counts mirror an IndexResult folded
// over every root, plus Pruned (orphan paths dropped when every root was present).
type SweepResult struct {
	Skipped      bool         `json:"skipped,omitempty"`
	Roots        int          `json:"roots"`
	Present      int          `json:"present"`
	Pruned       []string     `json:"pruned,omitempty"`
	Indexed      int          `json:"indexed"`
	Chunks       int          `json:"chunks"`
	Unchanged    int          `json:"unchanged"`
	SkippedFiles int          `json:"skipped_files"`
	Errors       []IndexError `json:"errors,omitempty"`
	Error        string       `json:"error,omitempty"`
}
