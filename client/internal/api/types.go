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

// IndexResult is the body returned by POST /index.
type IndexResult struct {
	Indexed int          `json:"indexed"` // files that produced at least one chunk
	Chunks  int          `json:"chunks"`  // total chunks written
	Skipped int          `json:"skipped"` // files skipped (empty/binary/oversized)
	Errors  []IndexError `json:"errors"`
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
	ID       string  `json:"id"`
	DocID    string  `json:"doc_id"`
	Path     string  `json:"path"`
	Score    float64 `json:"score"`
	Text     string  `json:"text"`
	ChunkIdx int     `json:"chunk_idx"`
	MTime    int64   `json:"mtime"`
}
