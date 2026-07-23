// Package mcp is the `otzer mcp` adapter: an MCP server (over stdio) whose tools
// proxy into the running Clojure server's HTTP API. It's read-only by design —
// search, get_document, list_indexed_paths — so an LLM host can find and read the
// user's documents but not mutate the index (plan §13).
//
// The SDK's own package is also called `mcp`, so it's imported here as `mcpsdk`.
package mcp

import (
	"context"

	mcpsdk "github.com/modelcontextprotocol/go-sdk/mcp"

	"otzer/internal/api"
)

const version = "0.1.0"

// NewServer builds the MCP server with the three read-only tools, each proxying
// to the otzer HTTP server via c.
func NewServer(c *api.Client) *mcpsdk.Server {
	s := mcpsdk.NewServer(&mcpsdk.Implementation{Name: "otzer", Version: version}, nil)
	addSearch(s, c)
	addGetDocument(s, c)
	addListIndexedPaths(s, c)
	return s
}

// Run serves MCP over stdin/stdout until the host disconnects (stdin EOF) or ctx
// is cancelled. It does not touch server lifecycle — the caller ensures the server
// is up beforehand and leaves it running afterwards (plan §13.4).
func Run(ctx context.Context, c *api.Client) error {
	return NewServer(c).Run(ctx, &mcpsdk.StdioTransport{})
}
