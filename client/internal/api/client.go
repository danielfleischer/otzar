package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"myrag/internal/lifecycle"
)

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
		return fmt.Errorf("GET %s: %s: %s", path, resp.Status, string(body))
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
		return fmt.Errorf("POST %s: %s: %s", path, resp.Status, string(b))
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

// Index posts to /index to index the given paths.
func (c *Client) Index(req IndexRequest) (*IndexResult, error) {
	var r IndexResult
	if err := c.postJSON("/index", req, &r); err != nil {
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
