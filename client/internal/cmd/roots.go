package cmd

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"

	"otzar/internal/render"
)

// rootsCmd is the read-only view of the *root list* — the trees you asked otzar
// to keep indexed, which the background watch sweep re-scans. Nothing here writes
// it: `otzar index` adds a root and `otzar unindex` forgets one, because a root is
// a side effect of what you ask of the index, not a list to edit on its own.
var rootsCmd = &cobra.Command{
	Use:   "roots",
	Short: "Show the trees otzar keeps indexed",
	Long: `The root list is what you asked otzar to keep indexed — every path you have
named to 'otzar index'. The watch sweep re-scans these on a timer, and 'otzar
index --now' re-scans them on demand. 'otzar unindex' removes one.

It is not the same as the index contents ('otzar list'): a file indexed
individually appears there and not here, and a root whose files were all skipped
appears here and not there.`,
}

var rootsListJSON bool

var rootsListCmd = &cobra.Command{
	Use:     "list",
	Aliases: []string{"ls"},
	Short:   "List the trees otzar keeps indexed",
	Long: `Print every recorded root, one per line, marked -r when it is indexed
recursively.

Output is bare paths so it pipes: 'otzar roots list | wc -l'.`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		c, err := client()
		if err != nil {
			return err
		}
		res, err := c.Roots()
		if err != nil {
			return err
		}
		if rootsListJSON {
			return render.JSON(res)
		}
		for _, r := range res.Roots {
			flag := ""
			if r.Recursive {
				flag = "  -r"
			}
			// Say so when a recorded root has gone from disk: that is what stops
			// the watch sweep pruning, so it is worth surfacing rather than
			// leaving the user to wonder why deletions never take effect.
			gone := ""
			if _, err := os.Stat(r.Path); err != nil {
				gone = "  (not on disk)"
			}
			fmt.Printf("%s%s%s\n", r.Path, flag, gone)
		}
		if len(res.Roots) == 0 {
			fmt.Fprintln(os.Stderr, "no roots recorded yet — try 'otzar index -r <dir>'")
		}
		return nil
	},
}

func init() {
	rootsListCmd.Flags().BoolVarP(&rootsListJSON, "json", "j", false, "emit raw JSON")
	rootsCmd.AddCommand(rootsListCmd)
}
