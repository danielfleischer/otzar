package api

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"

	"otzer/internal/lifecycle"
)

// serverError turns a non-2xx response into a Go error. The server sends JSON
// {"error": "...", "data": {...}} for handled failures (rag.http/with-errors), so
// we surface that message verbatim — it may be multi-line and carry remediation
// steps (e.g. the dimension-mismatch guidance to run `otzer admin clear` then
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

// Client talks to the running server. Its base URL is resolved from the port
// file the server publishes, so a Client is only valid while a server is up.
type Client struct {
	BaseURL string
	http    *http.Client
}

// New builds a Client pointed at the currently running server, or errors if no
// port file is present (i.e. the server isn't running).
func New() (*Client, error) {
	return NewWithTimeout(10 * time.Second)
}

// NewWithTimeout is New with an explicit per-request timeout. Indexing a large
// tree can take a while, so the index command asks for a longer budget.
func NewWithTimeout(d time.Duration) (*Client, error) {
	base, err := lifecycle.BaseURL()
	if err != nil {
		return nil, fmt.Errorf("server not running (no port file): %w", err)
	}
	return &Client{BaseURL: base, http: &http.Client{Timeout: d}}, nil
}

func (c *Client) getJSON(path string, out any) error {
	resp, err := c.http.Get(c.BaseURL + path)
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
	resp, err := c.http.Post(c.BaseURL+path, "application/json", &buf)
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

func (c *Client) deleteJSON(path string, out any) error {
	req, err := http.NewRequest(http.MethodDelete, c.BaseURL+path, nil)
	if err != nil {
		return err
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

// IndexStream posts to /index and consumes the NDJSON progress stream, invoking
// onEvent for each "start"/"file" event (nil to ignore them) and returning the
// terminal "result" summary. An "error" event becomes a Go error, as does a
// stream that ends without a result.
func (c *Client) IndexStream(req IndexRequest, onEvent func(IndexEvent)) (*IndexResult, error) {
	var buf bytes.Buffer
	if err := json.NewEncoder(&buf).Encode(req); err != nil {
		return nil, err
	}
	resp, err := c.http.Post(c.BaseURL+"/index", "application/json", &buf)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return nil, serverError("POST", "/index", resp.Status, b)
	}

	sc := bufio.NewScanner(resp.Body)
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
		return nil, err
	}
	if result == nil {
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
func (c *Client) Clear() (*ClearResult, error) {
	var r ClearResult
	if err := c.deleteJSON("/index", &r); err != nil {
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
