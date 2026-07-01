package cmd

import (
	"fmt"
	"strings"

	"github.com/spf13/cobra"

	"myrag/internal/api"
	"myrag/internal/render"
)

var (
	queryK    int
	queryMode string
	queryExt  []string
	queryJSON bool
)

var queryCmd = &cobra.Command{
	Use:   "query <text>...",
	Short: "Search the index",
	Long: `Search the index and print ranked chunks. In M1 only --mode keyword
(BM25) is available; vector and hybrid modes arrive in later milestones.`,
	Args: cobra.MinimumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		req := api.QueryRequest{
			Q:    strings.Join(args, " "),
			K:    queryK,
			Mode: queryMode,
		}
		if len(queryExt) > 0 {
			req.Filters = map[string]any{"ext": queryExt}
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
	queryCmd.Flags().StringVar(&queryMode, "mode", "keyword",
		"retrieval mode: keyword (vector/hybrid land in later milestones)")
	queryCmd.Flags().StringSliceVar(&queryExt, "ext", nil,
		"filter by file extension, e.g. --ext md,txt")
	queryCmd.Flags().BoolVar(&queryJSON, "json", false, "emit raw JSON")
}
