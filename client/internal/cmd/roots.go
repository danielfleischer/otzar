package cmd

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/spf13/cobra"

	"otzar/internal/render"
)

// rootsCmd groups the verbs over the *root list* — the trees you asked otzar to
// keep indexed, which the background watch sweep re-scans. That list is a
// different thing from the index itself: `otzar list` is what actually made it in,
// this is what you asked for. Editing it used to mean hand-editing
// ~/.otzar/roots.edn.
var rootsCmd = &cobra.Command{
	Use:   "roots",
	Short: "Manage the trees otzar keeps indexed",
	Long: `The root list is what you asked otzar to keep indexed — every path you have
named to 'otzar index'. The watch sweep re-scans these on a timer, and 'otzar
index --now' re-scans them on demand.

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

var rootsRemoveJSON bool

var rootsRemoveCmd = &cobra.Command{
	Use:     "remove <path>...",
	Aliases: []string{"rm"},
	Short:   "Stop keeping a tree indexed",
	Long: `Forget one or more roots, so the watch sweep stops re-scanning them.

This removes an intention, not any content: the chunks already indexed under
those paths stay searchable. To drop those too, delete the files and run
'otzar admin prune', or wipe everything with 'otzar admin clear'.

Paths are resolved against the current directory, the same way 'otzar index'
resolves them, and are not checked for existence — a root worth forgetting is
often one whose directory is already gone.`,
	Args: cobra.MinimumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		c, err := client()
		if err != nil {
			return err
		}

		// The server records absolute paths, so match on those.
		paths := make([]string, 0, len(args))
		for _, a := range args {
			abs, err := filepath.Abs(a)
			if err != nil {
				return err
			}
			paths = append(paths, abs)
		}

		res, err := c.RemoveRoots(paths)
		if err != nil {
			return err
		}
		if rootsRemoveJSON {
			return render.JSON(res)
		}

		render.KV([][2]string{
			{"removed", fmt.Sprintf("%d roots", len(res.Removed))},
			{"remaining", fmt.Sprintf("%d roots", len(res.Roots))},
		})
		for _, p := range res.Removed {
			fmt.Printf("  - %s\n", p)
		}
		// Naming a path that was never a root isn't an error, but silently doing
		// nothing would look like success — so say which ones didn't match.
		removed := map[string]bool{}
		for _, p := range res.Removed {
			removed[p] = true
		}
		for _, p := range paths {
			if !removed[p] {
				fmt.Fprintf(os.Stderr, "  ! %s is not a recorded root\n", p)
			}
		}
		return nil
	},
}

func init() {
	rootsListCmd.Flags().BoolVarP(&rootsListJSON, "json", "j", false, "emit raw JSON")
	rootsRemoveCmd.Flags().BoolVarP(&rootsRemoveJSON, "json", "j", false, "emit raw JSON")
	rootsCmd.AddCommand(rootsListCmd, rootsRemoveCmd)
}
