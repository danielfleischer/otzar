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

// IndexRequest is the body of POST /index.
type IndexRequest struct {
	Paths     []string `json:"paths"`
	Recursive bool     `json:"recursive"`
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
type Hit struct {
	ID             string          `json:"id"`
	DocID          string          `json:"doc_id"`
	Path           string          `json:"path"`
	Score          float64         `json:"score"`
	Text           string          `json:"text"`
	ChunkIdx       int             `json:"chunk_idx"`
	MTime          int64           `json:"mtime"`
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
	NumChunks int    `json:"num_chunks,omitempty"`
	Text      string `json:"text"`
	Stale     bool   `json:"stale,omitempty"` // file changed on disk since indexing
}

// PathsResult is the body returned by GET /paths.
type PathsResult struct {
	Count int      `json:"count"`
	Paths []string `json:"paths"`
}
