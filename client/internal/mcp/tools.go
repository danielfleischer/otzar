package mcp

import (
	"context"

	mcpsdk "github.com/modelcontextprotocol/go-sdk/mcp"

	"otzer/internal/api"
)

// The tool input/output types below double as the tools' JSON Schemas: the SDK
// infers each schema from the Go type and reads property descriptions from the
// `jsonschema` struct tags. Descriptions matter — the host shows them to the LLM
// as the basis for tool selection and argument filling (plan §13.3).

// SearchInput mirrors the useful subset of POST /query.
type SearchInput struct {
	Q          string   `json:"q" jsonschema:"the natural-language search query"`
	K          int      `json:"k,omitempty" jsonschema:"maximum number of hits to return (default 10)"`
	Mode       string   `json:"mode,omitempty" jsonschema:"retrieval mode: hybrid (default), keyword, or vector"`
	Ext        []string `json:"ext,omitempty" jsonschema:"restrict to these file extensions, e.g. [\"md\",\"clj\"]"`
	PathPrefix string   `json:"path_prefix,omitempty" jsonschema:"restrict to files whose absolute path starts with this string"`
	MtimeAfter string   `json:"mtime_after,omitempty" jsonschema:"restrict to files modified on or after this ISO-8601 date, e.g. 2025-01-01"`
}

// SearchHit is one hit, trimmed to what an LLM needs: the id to fetch the full
// text with get_document, the source path to cite, where in that source the chunk
// sits, the fused score, and the chunk text itself.
//
// The location is three fields, not two, because what you can cite depends on the
// format: locator says whether loc_start/loc_end count lines or pages, so the
// model can write file.md:42-47 for one and paper.pdf p.4 for the other. All
// three are omitted for chunks indexed before locator tracking — an absent field
// keeps the model from citing a range that was never computed.
type SearchHit struct {
	ID       string  `json:"id"`
	Path     string  `json:"path"`
	Locator  string  `json:"locator,omitempty"`
	LocStart int     `json:"loc_start,omitempty"`
	LocEnd   int     `json:"loc_end,omitempty"`
	Score    float64 `json:"score"`
	ChunkIdx int     `json:"chunk_idx"`
	Text     string  `json:"text"`
}

type SearchOutput struct {
	Hits []SearchHit `json:"hits"`
}

// GetDocumentInput is the argument to get_document.
type GetDocumentInput struct {
	ID string `json:"id" jsonschema:"a doc_id (whole file) or a chunk id of the form <doc_id>:<chunk_idx>, as returned by search"`
}

// noInput is the empty argument object for tools that take no parameters.
type noInput struct{}

func addSearch(s *mcpsdk.Server, c *api.Client) {
	mcpsdk.AddTool(s, &mcpsdk.Tool{
		Name: "search",
		Description: "Hybrid keyword+vector search over the user's local document index " +
			"(text, code, markdown, PDF). Returns ranked text chunks, each with its source " +
			"file path, the 1-based range the chunk spans (loc_start/loc_end) together with " +
			"the unit that range is in (locator: \"line\" or \"page\"), and an id you can pass " +
			"to get_document to read the full file. Cite a line range as path:42-47 and a " +
			"page range as path p.4 — never the other way round, and never invent a range " +
			"when the fields are absent. Use this to find where something is discussed " +
			"before answering questions about the user's documents or code.",
	}, func(_ context.Context, _ *mcpsdk.CallToolRequest, in SearchInput) (*mcpsdk.CallToolResult, SearchOutput, error) {
		req := api.QueryRequest{Q: in.Q, K: in.K, Mode: in.Mode}
		filters := map[string]any{}
		if len(in.Ext) > 0 {
			filters["ext"] = in.Ext
		}
		if in.PathPrefix != "" {
			filters["path_prefix"] = in.PathPrefix
		}
		if in.MtimeAfter != "" {
			filters["mtime_after"] = in.MtimeAfter
		}
		if len(filters) > 0 {
			req.Filters = filters
		}
		res, err := c.Query(req)
		if err != nil {
			return errorResult(err), SearchOutput{}, nil
		}
		out := SearchOutput{Hits: make([]SearchHit, 0, len(res.Hits))}
		for _, h := range res.Hits {
			out.Hits = append(out.Hits, SearchHit{
				ID: h.ID, Path: h.Path,
				Locator: h.Locator, LocStart: h.LocStart, LocEnd: h.LocEnd,
				Score: h.Score, ChunkIdx: h.ChunkIdx, Text: h.Text,
			})
		}
		return nil, out, nil
	})
}

func addGetDocument(s *mcpsdk.Server, c *api.Client) {
	mcpsdk.AddTool(s, &mcpsdk.Tool{
		Name: "get_document",
		Description: "Fetch the full text of an indexed item by id. Pass a doc_id to get the " +
			"whole source file (read fresh from disk, with original formatting), or a chunk id " +
			"(<doc_id>:<n>, as returned by search) to get just that chunk. A \"stale\": true " +
			"field on the result means the file changed on disk after it was indexed.",
	}, func(_ context.Context, _ *mcpsdk.CallToolRequest, in GetDocumentInput) (*mcpsdk.CallToolResult, api.Document, error) {
		doc, err := c.Document(in.ID)
		if err != nil {
			return errorResult(err), api.Document{}, nil
		}
		return nil, *doc, nil
	})
}

func addListIndexedPaths(s *mcpsdk.Server, c *api.Client) {
	mcpsdk.AddTool(s, &mcpsdk.Tool{
		Name: "list_indexed_paths",
		Description: "List the absolute paths of every source file currently in the index. " +
			"Use this to tell the user (or yourself) whether a given file or topic area is " +
			"actually available to search, instead of guessing.",
	}, func(_ context.Context, _ *mcpsdk.CallToolRequest, _ noInput) (*mcpsdk.CallToolResult, api.PathsResult, error) {
		res, err := c.Paths()
		if err != nil {
			return errorResult(err), api.PathsResult{}, nil
		}
		return nil, *res, nil
	})
}

// errorResult turns a proxy/HTTP failure into a tool-call error result (rather
// than a JSON-RPC protocol error), so the LLM sees the message and can react.
func errorResult(err error) *mcpsdk.CallToolResult {
	return &mcpsdk.CallToolResult{
		IsError: true,
		Content: []mcpsdk.Content{&mcpsdk.TextContent{Text: err.Error()}},
	}
}
