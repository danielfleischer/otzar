package api

import (
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
	base, err := lifecycle.BaseURL()
	if err != nil {
		return nil, fmt.Errorf("server not running (no port file): %w", err)
	}
	return &Client{BaseURL: base, http: &http.Client{Timeout: 10 * time.Second}}, nil
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
