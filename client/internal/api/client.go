package api

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sync/atomic"
	"time"

	"otzar/internal/lifecycle"
)

// serverError turns a non-2xx response into a Go error. The server sends JSON
// {"error": "...", "data": {...}} for handled failures (rag.http/with-errors), so
// we surface that message verbatim — it may be multi-line and carry remediation
// steps (e.g. the dimension-mismatch guidance to run `otzar admin clear` then
// re-index). Anything not in that shape (a proxy error, a raw stack) falls back to
// the method/status/body so nothing is silently swallowed.
func serverError(method, path, status string, body []byte) error {
	var e struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(body, &e) == nil && e.Error != "" {
		return errors.New(e.Error)
	}
	return fmt.Errorf("%s %s: %s: %s", method, path, status, string(body))
}

// stallTimeout bounds how long a streaming response may go completely silent
// before we give up on it. The server writes a heartbeat byte every two seconds
// while indexing (rag.http/heartbeat-secs), so silence this long means it is
// wedged, not merely busy — 30 missed heartbeats.
//
// This is deliberately *not* a cap on how long indexing may take. An
// http.Client.Timeout covers the whole exchange including reading the body, so
// using one here would kill a healthy multi-hour index run mid-stream with
// "context deadline exceeded ... while reading body". Liveness is the thing worth
// enforcing; total duration is the user's business.
const stallTimeout = 60 * time.Second

// Client talks to the running server. Its base URL is resolved from the port
// file the server publishes, so a Client is only valid while a server is up.
//
// Deadlines differ by call shape, which is why they live here rather than on the
// http.Client: unary calls get `timeout` as a total budget, while the streaming
// index gets `stall` as a limit on silence (see IndexStream). A zero value for
// either means "no deadline".
type Client struct {
	BaseURL string
	http    *http.Client
	timeout time.Duration
	stall   time.Duration
	ctx     context.Context // nil means context.Background(); see WithContext
}

// WithContext returns a copy of c whose unary requests also stop when ctx does,
// on top of the usual total deadline. The MCP tools use it so that a host
// cancelling a tool call abandons the HTTP request behind it.
func (c *Client) WithContext(ctx context.Context) *Client {
	bound := *c
	bound.ctx = ctx
	return &bound
}

// DefaultTimeout is New's total budget for a unary request. A variable rather
// than a literal so a test can shrink it and observe a deadline in milliseconds.
var DefaultTimeout = 10 * time.Second

// New builds a Client pointed at the currently running server, or errors if no
// port file is present (i.e. the server isn't running).
func New() (*Client, error) {
	return NewWithTimeout(DefaultTimeout)
}

// NewWithTimeout is New with an explicit total budget for unary (non-streaming)
// requests. It does not bound IndexStream, which is governed by stallTimeout.
func NewWithTimeout(d time.Duration) (*Client, error) {
	base, err := lifecycle.BaseURL()
	if err != nil {
		return nil, fmt.Errorf("server not running (no port file): %w", err)
	}
	return &Client{BaseURL: base, http: &http.Client{}, timeout: d, stall: stallTimeout}, nil
}

// request builds a unary request carrying the client's total deadline. The
// caller must defer the returned cancel func, and may only do so once the
// response body has been read — cancelling closes the body.
func (c *Client) request(method, path string, body io.Reader) (*http.Request, context.CancelFunc, error) {
	ctx, cancel := c.ctx, context.CancelFunc(func() {})
	if ctx == nil {
		ctx = context.Background()
	}
	if c.timeout > 0 {
		ctx, cancel = context.WithTimeout(ctx, c.timeout)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.BaseURL+path, body)
	if err != nil {
		cancel()
		return nil, nil, err
	}
	return req, cancel, nil
}

func (c *Client) getJSON(path string, out any) error {
	req, cancel, err := c.request(http.MethodGet, path, nil)
	if err != nil {
		return err
	}
	defer cancel()
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return serverError("GET", path, resp.Status, body)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

func (c *Client) postJSON(path string, body, out any) error {
	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			return err
		}
	}
	req, cancel, err := c.request(http.MethodPost, path, &buf)
	if err != nil {
		return err
	}
	defer cancel()
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return serverError("POST", path, resp.Status, b)
	}
	if out == nil {
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

// deleteJSON sends DELETE with an optional JSON body. DELETE carries a body here
// because both delete verbs take arguments (which roots to forget, whether a clear
// should forget them too) and a body keeps them in the same JSON shape as every
// other request rather than introducing query-string parsing on the server.
func (c *Client) deleteJSON(path string, body, out any) error {
	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			return err
		}
	}
	req, cancel, err := c.request(http.MethodDelete, path, &buf)
	if err != nil {
		return err
	}
	defer cancel()
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return serverError("DELETE", path, resp.Status, b)
	}
	if out == nil {
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

// Health fetches GET /health.
func (c *Client) Health() (*Health, error) {
	var h Health
	if err := c.getJSON("/health", &h); err != nil {
		return nil, err
	}
	return &h, nil
}

// Stats fetches GET /stats.
func (c *Client) Stats() (*Stats, error) {
	var s Stats
	if err := c.getJSON("/stats", &s); err != nil {
		return nil, err
	}
	return &s, nil
}

// Roots fetches GET /roots: the trees that have been indexed, each with the
// recursive flag it was indexed with. Post them back to /index to re-scan them.
func (c *Client) Roots() (*RootsResult, error) {
	var r RootsResult
	if err := c.getJSON("/roots", &r); err != nil {
		return nil, err
	}
	return &r, nil
}

// stallReader restarts a watchdog on every byte that arrives, so a stream is
// judged on whether it is *moving* rather than on how long it has been running.
// Reads, not lines, are what reset it: during one large file the server emits a
// JSON line only per embed batch, but heartbeat bytes keep arriving throughout,
// and those are the difference between "slow" and "dead".
type stallReader struct {
	r     io.Reader
	timer *time.Timer
	d     time.Duration
}

func (s *stallReader) Read(p []byte) (int, error) {
	n, err := s.r.Read(p)
	if n > 0 {
		s.timer.Reset(s.d)
	}
	return n, err
}

// IndexStream posts to /index and consumes the NDJSON progress stream, invoking
// onEvent for each "start"/"file" event (nil to ignore them) and returning the
// terminal "result" summary. An "error" event becomes a Go error, as does a
// stream that ends without a result.
//
// The run is bounded by silence (stallTimeout), never by total duration: indexing
// a large tree legitimately takes hours, and the server heartbeats throughout, so
// there is no honest reason to hang up on a stream that is still producing bytes.
// If we do hang up, the server notices the closed socket and cancels the run,
// keeping everything it had already committed.
func (c *Client) IndexStream(req IndexRequest, onEvent func(IndexEvent)) (*IndexResult, error) {
	var buf bytes.Buffer
	if err := json.NewEncoder(&buf).Encode(req); err != nil {
		return nil, err
	}
	return c.indexStream("/index", &buf, onEvent)
}

// ReindexStream posts to /reindex and consumes the same NDJSON progress stream as
// IndexStream. There is no request body: the server re-embeds what its index
// already holds, so there are no paths to send — and nothing it could mistake for
// a request to start tracking them.
func (c *Client) ReindexStream(onEvent func(IndexEvent)) (*IndexResult, error) {
	return c.indexStream("/reindex", http.NoBody, onEvent)
}

// indexStream drives one NDJSON indexing stream: POST `path`, feed every progress
// event to onEvent, and return the terminal "result".
func (c *Client) indexStream(path string, body io.Reader, onEvent func(IndexEvent)) (*IndexResult, error) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	hreq, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+path, body)
	if err != nil {
		return nil, err
	}
	hreq.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(hreq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return nil, serverError("POST", path, resp.Status, b)
	}

	// Cancelling the request is what unblocks a wedged read, so the watchdog has to
	// do that rather than just report — `stalled` then distinguishes our own
	// hang-up from a genuine transport failure when the read fails.
	var stalled atomic.Bool
	stream := io.Reader(resp.Body)
	if c.stall > 0 {
		watchdog := time.AfterFunc(c.stall, func() { stalled.Store(true); cancel() })
		defer watchdog.Stop()
		stream = &stallReader{r: resp.Body, timer: watchdog, d: c.stall}
	}
	stallErr := func() error {
		return fmt.Errorf("no progress from the server for %s — it stopped indexing when this "+
			"connection dropped, but files already committed are kept; check `otzar server status`", c.stall)
	}

	sc := bufio.NewScanner(stream)
	// A "result" line with many per-file errors can be large; lift the line cap.
	sc.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
	var result *IndexResult
	for sc.Scan() {
		line := bytes.TrimSpace(sc.Bytes()) // drop leading heartbeat spaces
		if len(line) == 0 {
			continue
		}
		var ev IndexEvent
		if err := json.Unmarshal(line, &ev); err != nil {
			return nil, fmt.Errorf("index stream: bad line %q: %w", line, err)
		}
		switch ev.Type {
		case "result":
			result = ev.Result
		case "error":
			return nil, fmt.Errorf("indexing failed: %s", ev.Error)
		default:
			if onEvent != nil {
				onEvent(ev)
			}
		}
	}
	if err := sc.Err(); err != nil {
		if stalled.Load() {
			return nil, stallErr()
		}
		return nil, err
	}
	if result == nil {
		if stalled.Load() {
			return nil, stallErr()
		}
		return nil, fmt.Errorf("index stream ended without a result")
	}
	return result, nil
}

// Prune posts to /prune to drop chunks for source files that no longer exist on
// disk (orphans from deletes/renames). With dryRun it only reports what would go.
func (c *Client) Prune(dryRun bool) (*PruneResult, error) {
	var r PruneResult
	if err := c.postJSON("/prune", PruneRequest{DryRun: dryRun}, &r); err != nil {
		return nil, err
	}
	return &r, nil
}

// Clear sends DELETE /index to wipe the whole index — every chunk of every file.
// Irreversible; the CLI gates it behind a confirmation. Returns the counts removed.
//
// With forgetRoots the server also empties roots.edn, which is what makes the wipe
// stick: roots are a standing instruction to keep those trees indexed, so left in
// place the next watch sweep re-indexes everything just cleared.
func (c *Client) Clear(forgetRoots bool) (*ClearResult, error) {
	var r ClearResult
	if err := c.deleteJSON("/index", ClearRequest{ForgetRoots: forgetRoots}, &r); err != nil {
		return nil, err
	}
	return &r, nil
}

// RemoveRoots sends DELETE /roots to stop indexing the given trees: it forgets
// them as roots and drops what is indexed beneath them. The paths need not be
// recorded roots — cleaning up after a tree you stopped indexing earlier is the
// same request. With dryRun the server changes nothing and reports what would go,
// which is what the confirmation prompt is built from.
func (c *Client) RemoveRoots(paths []string, dryRun bool) (*RemoveRootsResult, error) {
	var r RemoveRootsResult
	if err := c.deleteJSON("/roots", RemoveRootsRequest{Paths: paths, DryRun: dryRun}, &r); err != nil {
		return nil, err
	}
	return &r, nil
}

// Query posts to /query and returns the ranked hits.
func (c *Client) Query(req QueryRequest) (*QueryResult, error) {
	var r QueryResult
	if err := c.postJSON("/query", req, &r); err != nil {
		return nil, err
	}
	return &r, nil
}

// Document fetches GET /document/:id, where id is a doc_id (whole file) or a chunk
// id ("<doc_id>:<chunk_idx>"). A 404 (unknown id, or the file gone from disk)
// surfaces as an error.
func (c *Client) Document(id string) (*Document, error) {
	var d Document
	if err := c.getJSON("/document/"+url.PathEscape(id), &d); err != nil {
		return nil, err
	}
	return &d, nil
}

// Paths fetches GET /paths: the distinct source files currently indexed.
func (c *Client) Paths() (*PathsResult, error) {
	var p PathsResult
	if err := c.getJSON("/paths", &p); err != nil {
		return nil, err
	}
	return &p, nil
}

// Watch fetches GET /watch: the state of the background auto-reindex sweep.
func (c *Client) Watch() (*WatchStatus, error) {
	var w WatchStatus
	if err := c.getJSON("/watch", &w); err != nil {
		return nil, err
	}
	return &w, nil
}

// SetWatch posts to /watch to enable or disable the background sweep. When
// enabling, intervalS > 0 sets the interval (seconds); 0 keeps the current/default.
// Returns the resulting status.
func (c *Client) SetWatch(enabled bool, intervalS int) (*WatchStatus, error) {
	var w WatchStatus
	if err := c.postJSON("/watch", WatchRequest{Enabled: enabled, IntervalS: intervalS}, &w); err != nil {
		return nil, err
	}
	return &w, nil
}
