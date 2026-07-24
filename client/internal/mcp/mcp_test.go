package mcp_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	mcpsdk "github.com/modelcontextprotocol/go-sdk/mcp"

	"otzar/internal/api"
	"otzar/internal/mcp"
)

// stubServer stands in for the Clojure HTTP server, returning canned bodies for
// the three endpoints the MCP tools proxy to.
func stubServer(t *testing.T) *httptest.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("/query", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		if body["q"] == "" {
			http.Error(w, `{"error":"blank q"}`, 400)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"hits": []map[string]any{
			{"id": "d1:0", "doc_id": "d1", "path": "/notes/rag.md", "score": 0.81,
				"text": "chunk about retrieval", "chunk_idx": 0, "mtime": 1},
		}})
	})
	mux.HandleFunc("/document/", func(w http.ResponseWriter, r *http.Request) {
		id := strings.TrimPrefix(r.URL.Path, "/document/")
		switch id {
		case "d1":
			json.NewEncoder(w).Encode(map[string]any{"doc_id": "d1", "path": "/notes/rag.md",
				"ext": "md", "mtime": 1, "num_chunks": 2, "text": "line one\n\nline two"})
		case "d1:0":
			json.NewEncoder(w).Encode(map[string]any{"id": "d1:0", "doc_id": "d1",
				"path": "/notes/rag.md", "ext": "md", "mtime": 1, "chunk_idx": 0, "text": "line one"})
		default:
			http.Error(w, `{"error":"no such id"}`, 404)
		}
	})
	mux.HandleFunc("/paths", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"count": 2,
			"paths": []string{"/notes/rag.md", "/src/core.clj"}})
	})
	ts := httptest.NewServer(mux)
	t.Cleanup(ts.Close)
	return ts
}

// clientFor points an api.Client at ts by writing the port file api.New() reads,
// under an isolated HOME.
func clientFor(t *testing.T, ts *httptest.Server) *api.Client {
	home := t.TempDir()
	t.Setenv("HOME", home)
	port := mustPort(t, ts.URL)
	dir := filepath.Join(home, ".otzar")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "server.port"), []byte(port), 0o644); err != nil {
		t.Fatal(err)
	}
	c, err := api.New()
	if err != nil {
		t.Fatalf("api.New: %v", err)
	}
	return c
}

func mustPort(t *testing.T, rawURL string) string {
	u, err := url.Parse(rawURL)
	if err != nil {
		t.Fatal(err)
	}
	return u.Port()
}

// connect wires an MCP client to mcp.NewServer(c) over an in-memory transport.
func connect(t *testing.T, ctx context.Context, c *api.Client) *mcpsdk.ClientSession {
	serverT, clientT := mcpsdk.NewInMemoryTransports()
	srv := mcp.NewServer(c)
	go func() { _ = srv.Run(ctx, serverT) }()
	client := mcpsdk.NewClient(&mcpsdk.Implementation{Name: "test", Version: "0"}, nil)
	session, err := client.Connect(ctx, clientT, nil)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	t.Cleanup(func() { session.Close() })
	return session
}

// resultText flattens a tool result's content to a single string for assertions.
func resultText(t *testing.T, res *mcpsdk.CallToolResult) string {
	t.Helper()
	var sb strings.Builder
	for _, c := range res.Content {
		if tc, ok := c.(*mcpsdk.TextContent); ok {
			sb.WriteString(tc.Text)
		}
	}
	return sb.String()
}

func TestListTools(t *testing.T) {
	ctx := context.Background()
	session := connect(t, ctx, clientFor(t, stubServer(t)))

	res, err := session.ListTools(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]bool{}
	for _, tool := range res.Tools {
		got[tool.Name] = true
		if tool.Description == "" {
			t.Errorf("tool %q has no description (the host shows it to the LLM)", tool.Name)
		}
	}
	for _, want := range []string{"search", "get_document", "list_indexed_paths"} {
		if !got[want] {
			t.Errorf("missing tool %q; got %v", want, got)
		}
	}
}

func TestSearch(t *testing.T) {
	ctx := context.Background()
	session := connect(t, ctx, clientFor(t, stubServer(t)))

	res, err := session.CallTool(ctx, &mcpsdk.CallToolParams{
		Name: "search", Arguments: map[string]any{"q": "retrieval", "k": 3},
	})
	if err != nil {
		t.Fatal(err)
	}
	if res.IsError {
		t.Fatalf("search errored: %s", resultText(t, res))
	}
	txt := resultText(t, res)
	// The hit id is what lets the model follow up with get_document.
	if !strings.Contains(txt, "d1:0") || !strings.Contains(txt, "/notes/rag.md") {
		t.Errorf("search result missing id/path; got: %s", txt)
	}
}

func TestGetDocument(t *testing.T) {
	ctx := context.Background()
	session := connect(t, ctx, clientFor(t, stubServer(t)))

	// Whole file by doc_id: disk text with newlines preserved.
	res, err := session.CallTool(ctx, &mcpsdk.CallToolParams{
		Name: "get_document", Arguments: map[string]any{"id": "d1"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if res.IsError {
		t.Fatalf("get_document errored: %s", resultText(t, res))
	}
	// Content is JSON, so the faithful newlines appear escaped as \n.
	if txt := resultText(t, res); !strings.Contains(txt, `line one\n\nline two`) {
		t.Errorf("doc text not faithful; got: %s", txt)
	}

	// Unknown id -> the 404 surfaces as a tool-call error result, not a crash.
	res, err = session.CallTool(ctx, &mcpsdk.CallToolParams{
		Name: "get_document", Arguments: map[string]any{"id": "nope"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !res.IsError {
		t.Errorf("expected IsError for unknown id; got: %s", resultText(t, res))
	}
}

func TestListIndexedPaths(t *testing.T) {
	ctx := context.Background()
	session := connect(t, ctx, clientFor(t, stubServer(t)))

	res, err := session.CallTool(ctx, &mcpsdk.CallToolParams{Name: "list_indexed_paths"})
	if err != nil {
		t.Fatal(err)
	}
	if res.IsError {
		t.Fatalf("list_indexed_paths errored: %s", resultText(t, res))
	}
	txt := resultText(t, res)
	if !strings.Contains(txt, "/notes/rag.md") || !strings.Contains(txt, "/src/core.clj") {
		t.Errorf("paths result missing entries; got: %s", txt)
	}
}
