package cmd

import (
	"fmt"
	"path/filepath"
	"time"

	"github.com/spf13/cobra"

	"myrag/internal/api"
	"myrag/internal/render"
)

var (
	indexRecursive bool
	indexJSON      bool
)

var indexCmd = &cobra.Command{
	Use:   "index <path>...",
	Short: "Index files or folders into the search index",
	Long: `Walk the given files and folders, extract text, chunk it, and add the
chunks to the local index. Directories are only descended with --recursive.`,
	Args: cobra.MinimumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		// The server runs in its own working directory, so send absolute paths.
		paths := make([]string, 0, len(args))
		for _, a := range args {
			abs, err := filepath.Abs(a)
			if err != nil {
				return err
			}
			paths = append(paths, abs)
		}

		// Indexing can be slow on a large tree; give it a generous budget.
		c, err := api.NewWithTimeout(10 * time.Minute)
		if err != nil {
			return err
		}
		res, err := c.Index(api.IndexRequest{Paths: paths, Recursive: indexRecursive})
		if err != nil {
			return err
		}

		if indexJSON {
			return render.JSON(res)
		}

		render.KV([][2]string{
			{"indexed", fmt.Sprintf("%d files", res.Indexed)},
			{"chunks", fmt.Sprintf("%d", res.Chunks)},
			{"skipped", fmt.Sprintf("%d files", res.Skipped)},
			{"errors", fmt.Sprintf("%d", len(res.Errors))},
		})
		for _, e := range res.Errors {
			fmt.Printf("  ! %s: %s\n", e.Path, e.Error)
		}
		return nil
	},
}

func init() {
	indexCmd.Flags().BoolVarP(&indexRecursive, "recursive", "r", false,
		"descend into subdirectories")
	indexCmd.Flags().BoolVar(&indexJSON, "json", false, "emit raw JSON")
}
