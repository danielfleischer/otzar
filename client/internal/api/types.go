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
