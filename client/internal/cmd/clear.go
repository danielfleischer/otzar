package cmd

import (
	"bufio"
	"fmt"
	"os"
	"strings"

	"github.com/spf13/cobra"

	"myrag/internal/api"
	"myrag/internal/render"
)

var (
	clearYes  bool
	clearJSON bool
)

var clearCmd = &cobra.Command{
	Use:   "clear",
	Short: "Wipe the entire index (every chunk of every file)",
	Long: `Delete everything in the index — every chunk of every file — in one step. The
source files on disk are untouched; only the index is dropped, so you can rebuild
it by indexing again.

This is the supported reset after changing the embedding model to one with a
different vector dimension, which the index can't accept in place: clear, then
re-index.

The wipe is irreversible, so clear asks for confirmation first; pass --yes to
skip the prompt (e.g. in scripts).`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		c, err := api.New()
		if err != nil {
			return err
		}

		if !clearYes {
			ok, err := confirmClear(c)
			if err != nil {
				return err
			}
			if !ok {
				fmt.Fprintln(os.Stderr, "aborted")
				return nil
			}
		}

		res, err := c.Clear()
		if err != nil {
			return err
		}
		if clearJSON {
			return render.JSON(res)
		}
		render.KV([][2]string{
			{"cleared", fmt.Sprintf("%d files", res.RemovedDocs)},
			{"chunks", fmt.Sprintf("%d", res.RemovedChunks)},
		})
		return nil
	},
}

// confirmClear shows what will be wiped and reads a yes/no from the terminal. The
// prompt goes to stderr so a --json stdout stays clean. Refuses (rather than
// hanging on a pipe) when stdin isn't interactive — the caller should pass --yes.
func confirmClear(c *api.Client) (bool, error) {
	if !isTerminal(os.Stdin) {
		return false, fmt.Errorf("refusing to clear without confirmation; pass --yes to skip the prompt")
	}
	s, err := c.Stats()
	if err != nil {
		return false, err
	}
	fmt.Fprintf(os.Stderr, "This will delete all %d files (%d chunks) from the index. This cannot be undone.\n",
		s.NumDocs, s.NumChunks)
	fmt.Fprint(os.Stderr, "Type 'yes' to continue: ")
	line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
	return strings.TrimSpace(line) == "yes", nil
}

func init() {
	clearCmd.Flags().BoolVarP(&clearYes, "yes", "y", false,
		"skip the confirmation prompt")
	clearCmd.Flags().BoolVarP(&clearJSON, "json", "j", false, "emit raw JSON")
}
