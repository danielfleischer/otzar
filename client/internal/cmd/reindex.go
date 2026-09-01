package cmd

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"

	"otzar/internal/render"
)

var (
	reindexYes  bool
	reindexJSON bool
)

var reindexCmd = &cobra.Command{
	Use:   "reindex",
	Short: "Re-embed every file already in the index (after a chunker/model change)",
	Long: `Rebuild the index from its own contents. The server re-runs the full pipeline
over every source file it already has indexed — you don't name paths — re-extracting,
re-chunking and re-embedding each, replacing that file's chunks. Unlike a plain
re-index it re-embeds every file even when nothing changed.

Reach for it after a change the incremental index can't detect on its own: a new
chunker, a chunk-schema field, or a switch to a different embedding model of the
same vector dimension. A change in embedding *dimension* can't be applied in
place — run 'otzar admin clear' first, then index.

Re-embedding calls the embedder for every chunk and can take a while, so reindex
confirms first; pass --yes to skip. Files deleted from disk since indexing are
skipped silently (use 'otzar admin prune' to drop them). Your indexed roots are
untouched: this rebuilds the index, it doesn't ask for anything new to be tracked.`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		// Re-embedding a whole index is slow, but IndexStream is bounded by silence
		// rather than by duration, so the default client is right here too.
		c, err := client()
		if err != nil {
			return err
		}

		p, err := c.Paths()
		if err != nil {
			return err
		}
		if p.Count == 0 {
			fmt.Println("nothing indexed")
			return nil
		}

		if !reindexYes {
			prompt := fmt.Sprintf("Re-embed all %d indexed files? This calls the embedder for every chunk. [y/N]: ", p.Count)
			ok, err := confirm(prompt, false)
			if err != nil {
				return err
			}
			if !ok {
				fmt.Fprintln(os.Stderr, "aborted")
				return nil
			}
		}

		// The server picks the files (its own index contents) and forces the
		// re-embed; /paths above is only for the count in the prompt.
		bar := newProgressBar(os.Stderr, !reindexJSON && isTerminal(os.Stderr))
		res, err := c.ReindexStream(bar.update)
		bar.finish()
		if err != nil {
			return err
		}

		if reindexJSON {
			return render.JSON(res)
		}

		kv := [][2]string{
			{"reindexed", fmt.Sprintf("%d files", res.Indexed)},
			{"chunks", fmt.Sprintf("%d", res.Chunks)},
			{"skipped", fmt.Sprintf("%d files", res.Skipped)},
			{"errors", fmt.Sprintf("%d", len(res.Errors))},
		}
		if res.Removed > 0 {
			kv = append(kv, [2]string{"removed", fmt.Sprintf("%d files no longer readable", res.Removed)})
		}
		if res.Cancelled {
			kv = append(kv, [2]string{"cancelled", "yes (client disconnected)"})
		}
		render.KV(kv)
		for _, e := range res.Errors {
			fmt.Printf("  ! %s: %s\n", e.Path, e.Error)
		}
		return nil
	},
}

func init() {
	reindexCmd.Flags().BoolVarP(&reindexYes, "yes", "y", false, "skip the confirmation prompt")
	reindexCmd.Flags().BoolVarP(&reindexJSON, "json", "j", false, "emit raw JSON")
}
