package cmd

import (
	"fmt"
	"strings"

	"github.com/spf13/cobra"

	"otzer/internal/api"
	"otzer/internal/render"
)

var (
	queryK     int
	queryMode  string
	queryExt   []string
	queryPath  string
	queryAfter string
	queryJSON  bool
)

var queryCmd = &cobra.Command{
	Use:   "query <text>...",
	Short: "Search the index",
	Long: `Search the index and print ranked chunks. --mode selects the retriever:
keyword (BM25), vector (kNN over embeddings), or hybrid (RRF fusion of both).
--ext, --path, and --after apply metadata filters to any mode.`,
	Args: cobra.MinimumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		req := api.QueryRequest{
			Q:    strings.Join(args, " "),
			K:    queryK,
			Mode: queryMode,
		}
		filters := map[string]any{}
		if len(queryExt) > 0 {
			filters["ext"] = queryExt
		}
		if queryPath != "" {
			filters["path_prefix"] = queryPath
		}
		if queryAfter != "" {
			filters["mtime_after"] = queryAfter
		}
		if len(filters) > 0 {
			req.Filters = filters
		}

		c, err := api.New()
		if err != nil {
			return err
		}
		res, err := c.Query(req)
		if err != nil {
			return err
		}

		if queryJSON {
			return render.JSON(res)
		}

		if len(res.Hits) == 0 {
			fmt.Println("no matches")
			return nil
		}

		rows := make([][]string, 0, len(res.Hits))
		for i, h := range res.Hits {
			rows = append(rows, []string{
				fmt.Sprintf("%d", i+1),
				fmt.Sprintf("%.3f", h.Score),
				location(h),
				snippet(h.Text),
			})
		}
		render.Table([]string{"#", "score", "path", "snippet"}, rows)
		return nil
	},
}

// location renders a hit's source as path:line for citation — "file.md:42-47", or
// "file.md:42" for a single line. Falls back to the bare path when the chunk has
// no line range (older index, or an extractor without source lines).
func location(h api.Hit) string {
	switch {
	case h.LineStart == 0:
		return h.Path
	case h.LineEnd > h.LineStart:
		return fmt.Sprintf("%s:%d-%d", h.Path, h.LineStart, h.LineEnd)
	default:
		return fmt.Sprintf("%s:%d", h.Path, h.LineStart)
	}
}

// snippet collapses a chunk to a single truncated line for table display.
func snippet(text string) string {
	s := strings.Join(strings.Fields(text), " ")
	const max = 72
	if len(s) > max {
		s = s[:max-1] + "…"
	}
	return s
}

func init() {
	queryCmd.Flags().IntVarP(&queryK, "k", "k", 10, "number of results")
	queryCmd.Flags().StringVarP(&queryMode, "mode", "m", "hybrid",
		"retrieval mode: hybrid | keyword | vector")
	queryCmd.Flags().StringSliceVarP(&queryExt, "ext", "e", nil,
		"filter by file extension, e.g. -e md,txt")
	queryCmd.Flags().StringVarP(&queryPath, "path", "p", "",
		"filter to paths under this prefix, e.g. -p /notes/")
	queryCmd.Flags().StringVarP(&queryAfter, "after", "a", "",
		"filter to files modified on/after this date, e.g. -a 2025-01-01")
	queryCmd.Flags().BoolVarP(&queryJSON, "json", "j", false, "emit raw JSON")
}
