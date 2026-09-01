package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// testClient points a Client at a stub server (same package, so it can set the
// unexported fields directly instead of going through the port file).
func testClient(srv *httptest.Server) *Client {
	return &Client{BaseURL: srv.URL, http: srv.Client(), timeout: 10 * time.Second, stall: stallTimeout}
}

// A dimension-mismatch 400 from /query must surface the server's remediation
// message cleanly — with real newlines and the exact commands — not the raw JSON
// envelope.
func TestQuerySurfacesServerErrorMessage(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(`{"error":"Embedding dimension changed: the index holds 1024-dim vectors but the configured model now produces 768-dim.\n  otzar admin clear\n  otzar index <paths…>","data":{"type":"dimension-mismatch","indexed":1024,"configured":768}}`))
	}))
	defer srv.Close()

	_, err := testClient(srv).Query(QueryRequest{Q: "x", Mode: "vector"})
	if err == nil {
		t.Fatal("expected an error from a 400 response")
	}
	if !strings.Contains(err.Error(), "otzar admin clear") ||
		!strings.Contains(err.Error(), "otzar index") {
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
		w.Write([]byte(`{"type":"error","error":"Embedding dimension changed.\n  otzar admin clear\n  otzar index <paths…>"}` + "\n"))
	}))
	defer srv.Close()

	_, err := testClient(srv).IndexStream(IndexRequest{Paths: []string{"/a.md"}}, nil)
	if err == nil {
		t.Fatal("expected an error from the stream's error event")
	}
	if !strings.Contains(err.Error(), "otzar admin clear") {
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

// A long index run must not be killed for being long. This is the regression
// behind "context deadline exceeded ... while reading body": the client used to
// put a total timeout on the http.Client, which covers reading the body, so any
// run outlasting it died mid-stream even while the server was making progress.
// Here the stream takes well over the client's stall budget but never goes quiet
// for that long, and must complete.
func TestIndexStreamSurvivesASlowButLiveStream(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/x-ndjson")
		flusher := w.(http.Flusher)
		// Heartbeat bytes only, spread over ~5x the stall budget, then the result.
		for i := 0; i < 10; i++ {
			w.Write([]byte(" "))
			flusher.Flush()
			time.Sleep(10 * time.Millisecond)
		}
		fmt.Fprintln(w, `{"type":"result","result":{"indexed":1,"chunks":2}}`)
	}))
	defer srv.Close()

	c := testClient(srv)
	c.stall = 20 * time.Millisecond
	res, err := c.IndexStream(IndexRequest{Paths: []string{"/x"}}, nil)
	if err != nil {
		t.Fatalf("live stream was cut off: %v", err)
	}
	if res.Indexed != 1 || res.Chunks != 2 {
		t.Fatalf("unexpected result: %+v", res)
	}
}

// The flip side: a stream that goes genuinely silent is abandoned, with a message
// that says what happened to the indexing rather than leaking "context canceled".
func TestIndexStreamGivesUpOnASilentStream(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/x-ndjson")
		w.(http.Flusher).Flush()
		<-release // never a byte, until the test tears the server down
	}))
	defer func() { close(release); srv.Close() }()

	c := testClient(srv)
	c.stall = 30 * time.Millisecond
	_, err := c.IndexStream(IndexRequest{Paths: []string{"/x"}}, nil)
	if err == nil {
		t.Fatal("expected a stall error")
	}
	if !strings.Contains(err.Error(), "no progress from the server") {
		t.Fatalf("stall error should explain itself, got: %v", err)
	}
	if !strings.Contains(err.Error(), "already committed are kept") {
		t.Fatalf("stall error should say committed work survives, got: %v", err)
	}
}

// Clear and RemoveRoots send DELETE with a JSON body — DELETE is not usually a
// verb with a payload, so the encoding is worth pinning: an argument silently
// dropped here would look like a working command that quietly did the safer thing.
func TestClearSendsForgetRootsInTheBody(t *testing.T) {
	for _, forget := range []bool{true, false} {
		var got struct {
			ForgetRoots bool `json:"forget_roots"`
		}
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodDelete {
				t.Errorf("clear used %s, want DELETE", r.Method)
			}
			if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
				t.Errorf("clear body did not decode: %v", err)
			}
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"removed_docs":2,"removed_chunks":9,"forgotten_roots":["/notes"]}`))
		}))

		res, err := testClient(srv).Clear(forget)
		srv.Close()
		if err != nil {
			t.Fatalf("Clear(%v): %v", forget, err)
		}
		if got.ForgetRoots != forget {
			t.Errorf("server saw forget_roots=%v, want %v", got.ForgetRoots, forget)
		}
		if res.RemovedDocs != 2 || res.RemovedChunks != 9 {
			t.Errorf("counts not decoded: %+v", res)
		}
		if len(res.ForgottenRoots) != 1 || res.ForgottenRoots[0] != "/notes" {
			t.Errorf("forgotten roots not decoded: %+v", res.ForgottenRoots)
		}
	}
}

func TestRemoveRootsSendsThePaths(t *testing.T) {
	var got RemoveRootsRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodDelete || r.URL.Path != "/roots" {
			t.Errorf("got %s %s, want DELETE /roots", r.Method, r.URL.Path)
		}
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Errorf("body did not decode: %v", err)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"removed":["/a"],"roots":[{"path":"/b","recursive":true}]}`))
	}))
	defer srv.Close()

	res, err := testClient(srv).RemoveRoots([]string{"/a", "/missing"})
	if err != nil {
		t.Fatalf("RemoveRoots: %v", err)
	}
	if len(got.Paths) != 2 || got.Paths[0] != "/a" || got.Paths[1] != "/missing" {
		t.Errorf("server saw paths %v, want both", got.Paths)
	}
	// Only /a came back as removed: the caller needs that to tell the user
	// /missing was never a root, so it must not be inferred from the request.
	if len(res.Removed) != 1 || res.Removed[0] != "/a" {
		t.Errorf("removed = %v, want [/a]", res.Removed)
	}
	if len(res.Roots) != 1 || res.Roots[0].Path != "/b" {
		t.Errorf("remaining roots = %+v, want [/b]", res.Roots)
	}
}

// A 409 means another index mutation holds the lock. The message is the server's
// advice to retry, and it has to reach the user intact rather than as a status code.
func TestBusyIndexSurfacesTheServersRetryAdvice(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		w.Write([]byte(`{"error":"clear can't run right now: an index run is in progress. Wait for it to finish and try again.","data":{"type":"index-busy"}}`))
	}))
	defer srv.Close()

	_, err := testClient(srv).Clear(false)
	if err == nil {
		t.Fatal("expected an error from a 409 response")
	}
	if !strings.Contains(err.Error(), "an index run is in progress") {
		t.Fatalf("retry advice missing: %q", err)
	}
	for _, leak := range []string{"409", "Conflict", `"data"`} {
		if strings.Contains(err.Error(), leak) {
			t.Fatalf("error leaked the envelope %q: %q", leak, err)
		}
	}
}

// The stale-chunk fix added a counter to the index result. The CLI only prints it
// when non-zero, so a decoding slip would be invisible rather than loud.
func TestIndexResultDecodesRemoved(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/x-ndjson")
		w.Write([]byte(`{"type":"result","result":{"indexed":1,"chunks":3,"skipped":0,"unchanged":2,"removed":4,"errors":[]}}` + "\n"))
	}))
	defer srv.Close()

	res, err := testClient(srv).IndexStream(IndexRequest{Paths: []string{"/a.md"}}, nil)
	if err != nil {
		t.Fatalf("IndexStream: %v", err)
	}
	if res.Removed != 4 {
		t.Errorf("removed = %d, want 4", res.Removed)
	}
	if res.Unchanged != 2 || res.Indexed != 1 {
		t.Errorf("other counters disturbed: %+v", res)
	}
}
