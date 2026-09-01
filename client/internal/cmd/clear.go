package cmd

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"

	"otzar/internal/render"
)

var (
	clearYes         bool
	clearJSON        bool
	clearForgetRoots bool
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

Clearing does not forget the trees you asked to index. That is usually what you
want — clear, then re-index the same trees at the new model — but it means the
background watch sweep will re-index them on its next tick. Pass --forget-roots to
empty that list too, so the wipe stays wiped until you name something again.

The wipe is irreversible, so clear asks for confirmation first; pass --yes to
skip the prompt (e.g. in scripts).`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		c, err := client()
		if err != nil {
			return err
		}

		if !clearYes {
			s, err := c.Stats()
			if err != nil {
				return err
			}
			also := ""
			if clearForgetRoots {
				also = " It will also forget every tree you asked to index."
			}
			prompt := fmt.Sprintf(
				"This will delete all %d files (%d chunks) from the index.%s This cannot be undone.\nType 'yes' to continue: ",
				s.NumDocs, s.NumChunks, also)
			ok, err := confirm(prompt, true)
			if err != nil {
				return err
			}
			if !ok {
				fmt.Fprintln(os.Stderr, "aborted")
				return nil
			}
		}

		res, err := c.Clear(clearForgetRoots)
		if err != nil {
			return err
		}
		if clearJSON {
			return render.JSON(res)
		}
		kv := [][2]string{
			{"cleared", fmt.Sprintf("%d files", res.RemovedDocs)},
			{"chunks", fmt.Sprintf("%d", res.RemovedChunks)},
		}
		if clearForgetRoots {
			kv = append(kv, [2]string{"forgot", fmt.Sprintf("%d roots", len(res.ForgottenRoots))})
		}
		render.KV(kv)
		for _, p := range res.ForgottenRoots {
			fmt.Printf("  - %s\n", p)
		}
		return nil
	},
}

func init() {
	clearCmd.Flags().BoolVarP(&clearYes, "yes", "y", false,
		"skip the confirmation prompt")
	clearCmd.Flags().BoolVarP(&clearJSON, "json", "j", false, "emit raw JSON")
	clearCmd.Flags().BoolVar(&clearForgetRoots, "forget-roots", false,
		"also forget the trees you asked to index, so the watch sweep doesn't re-index them")
}
