package api

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// testClient points a Client at a stub server (same package, so it can set the
// unexported fields directly instead of going through the port file).
func testClient(srv *httptest.Server) *Client {
	return &Client{BaseURL: srv.URL, http: srv.Client()}
}

// A dimension-mismatch 400 from /query must surface the server's remediation
// message cleanly — with real newlines and the exact commands — not the raw JSON
// envelope.
func TestQuerySurfacesServerErrorMessage(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(`{"error":"Embedding dimension changed: the index holds 1024-dim vectors but the configured model now produces 768-dim.\n  otzer admin clear\n  otzer index <paths…>","data":{"type":"dimension-mismatch","indexed":1024,"configured":768}}`))
	}))
	defer srv.Close()

	_, err := testClient(srv).Query(QueryRequest{Q: "x", Mode: "vector"})
	if err == nil {
		t.Fatal("expected an error from a 400 response")
	}
	if !strings.Contains(err.Error(), "otzer admin clear") ||
		!strings.Contains(err.Error(), "otzer index") {
		t.Fatalf("remediation commands missing from error: %q", err)
	}
	// The JSON envelope (data/type/status) must not leak into the user-facing message.
	for _, leak := range []string{`"data"`, `"type"`, "400", "Bad Request"} {
		if strings.Contains(err.Error(), leak) {
			t.Fatalf("error should be the clean server message, but leaked %q: %q", leak, err)
		}
	}
}

// A mismatch mid-index arrives as an NDJSON "error" event; IndexStream turns it
// into a Go error carrying the same remediation text.
func TestIndexStreamErrorEventBecomesError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/x-ndjson")
		w.Write([]byte(`{"type":"start","total":1}` + "\n"))
		w.Write([]byte(`{"type":"error","error":"Embedding dimension changed.\n  otzer admin clear\n  otzer index <paths…>"}` + "\n"))
	}))
	defer srv.Close()

	_, err := testClient(srv).IndexStream(IndexRequest{Paths: []string{"/a.md"}}, nil)
	if err == nil {
		t.Fatal("expected an error from the stream's error event")
	}
	if !strings.Contains(err.Error(), "otzer admin clear") {
		t.Fatalf("remediation missing from index error: %q", err)
	}
}

// When a non-2xx body is not the server's JSON error shape (a proxy page, a raw
// stack), the client must fall back to the raw body so nothing is swallowed.
func TestNonJSONErrorFallsBackToRawBody(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("upstream exploded"))
	}))
	defer srv.Close()

	_, err := testClient(srv).Stats()
	if err == nil || !strings.Contains(err.Error(), "upstream exploded") {
		t.Fatalf("expected the raw body in the error, got: %v", err)
	}
}
