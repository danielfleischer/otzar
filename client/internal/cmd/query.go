package cmd

import (
	"fmt"
	"strings"

	"github.com/spf13/cobra"

	"otzar/internal/api"
	"otzar/internal/render"
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

		c, err := client()
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

// location renders a hit's source in the form you'd cite or paste it: line-based
// chunks as "file.md:42-47" (or "file.md:42" for one line), which editors and
// terminals turn into a jump; page-based ones as "paper.pdf p.4-5", since a PDF
// has no line to jump to; paragraph-based ones as "notes.docx ¶12", since a
// .docx has neither — its line and page breaks are decided at display time, and
// only the paragraph sequence is in the file. Falls back to the bare path when
// the chunk carries no span at all — a chunk indexed before locator tracking.
func location(h api.Hit) string {
	if h.LocStart == 0 {
		return h.Path
	}
	sep, span := ":", fmt.Sprintf("%d", h.LocStart)
	switch h.Locator {
	case "page":
		sep = " p."
	case "paragraph":
		sep = " ¶"
	}
	if h.LocEnd > h.LocStart {
		span = fmt.Sprintf("%d-%d", h.LocStart, h.LocEnd)
	}
	return h.Path + sep + span
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
