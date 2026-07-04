package cmd

import (
	"fmt"
	"strings"

	"github.com/spf13/cobra"

	"myrag/internal/api"
	"myrag/internal/render"
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
				h.Path,
				snippet(h.Text),
			})
		}
		render.Table([]string{"#", "score", "path", "snippet"}, rows)
		return nil
	},
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
	queryCmd.Flags().IntVar(&queryK, "k", 10, "number of results")
	queryCmd.Flags().StringVar(&queryMode, "mode", "hybrid",
		"retrieval mode: hybrid | keyword | vector")
	queryCmd.Flags().StringSliceVar(&queryExt, "ext", nil,
		"filter by file extension, e.g. --ext md,txt")
	queryCmd.Flags().StringVar(&queryPath, "path", "",
		"filter to paths under this prefix, e.g. --path /notes/")
	queryCmd.Flags().StringVar(&queryAfter, "after", "",
		"filter to files modified on/after this date, e.g. --after 2025-01-01")
	queryCmd.Flags().BoolVar(&queryJSON, "json", false, "emit raw JSON")
}
