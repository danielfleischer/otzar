package cmd

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/cobra"

	"otzar/internal/api"
	"otzar/internal/render"
)

var (
	unindexJSON   bool
	unindexYes    bool
	unindexDryRun bool
)

// unindexCmd is the inverse of `otzar index`: index adds a path to the index and
// remembers it as a root; unindex drops it from the index and forgets any root.
// It is named for what it does to the index, not to roots.edn, because the case
// that needs it most — a tree un-rooted earlier whose files were left behind — is
// one where the path is no root at all.
var unindexCmd = &cobra.Command{
	Use:   "unindex <path>...",
	Short: "Drop files or folders from the search index (the inverse of index)",
	Long: `Drop one or more paths from the index: every indexed file at or beneath them
goes, and any of them recorded as a root is forgotten, so the watch sweep stops
re-scanning it.

Your files on disk are never touched — only the index is. This is the one verb
that deletes on intent alone: 'otzar admin prune' drops files that are gone from
disk, and 'otzar admin clear' wipes everything, but neither can un-index a tree
you simply no longer want searchable.

A path need not be a root. Whatever is indexed beneath it goes either way — which
is how you clean up files left in the index by a tree that stopped being a root.
Files that another root still covers are kept, since the next sweep would only
index them again.

Paths are resolved against the current directory, the same way 'otzar index'
resolves them, and are not checked for existence — a tree worth un-indexing is
often one that is already gone, and its chunks are still in the index.

Dropping indexed files can't be undone (short of indexing them again), so this
asks for confirmation first; --yes skips the prompt and --dry-run shows what
would go without touching anything.`,
	Args: cobra.MinimumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		c, err := maintenanceClient()
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

		// A dry run is the same request with nothing written, so the preview the
		// user confirms is the server's own answer rather than our guess at it.
		if unindexDryRun {
			res, err := c.RemoveRoots(paths, true)
			if err != nil {
				return err
			}
			return reportUnindexed(res, paths)
		}

		if !unindexYes {
			preview, err := c.RemoveRoots(paths, true)
			if err != nil {
				return err
			}
			if len(preview.Forgotten) == 0 && len(preview.RemovedFiles) == 0 {
				return reportUnindexed(preview, paths)
			}
			// Forgetting a root is reversible (index it again); dropping indexed
			// files is not, so that is the only half worth a prompt.
			if n := len(preview.RemovedFiles); n > 0 {
				prompt := fmt.Sprintf(
					"This will drop %d indexed files (%s chunks) from the index. Your files on disk are untouched.\nContinue? [y/N]: ",
					n, commas(preview.RemovedChunks))
				ok, err := confirm(prompt, false)
				if err != nil {
					return err
				}
				if !ok {
					fmt.Fprintln(os.Stderr, "aborted")
					return nil
				}
			}
		}

		res, err := c.RemoveRoots(paths, false)
		if err != nil {
			return err
		}
		return reportUnindexed(res, paths)
	},
}

// reportUnindexed prints what an unindex did, or (on a dry run) would do. The
// two halves are reported separately because they are separate outcomes: a path
// that was never a root can still drop files, and a root can be forgotten with
// nothing indexed under it.
func reportUnindexed(res *api.RemoveRootsResult, asked []string) error {
	if unindexJSON {
		return render.JSON(res)
	}

	forgot, dropped := "forgot", "dropped"
	if res.DryRun {
		forgot, dropped = "would forget", "would drop"
	}
	kv := [][2]string{
		{dropped, fmt.Sprintf("%d files (%s chunks)", len(res.RemovedFiles), commas(res.RemovedChunks))},
		{forgot, fmt.Sprintf("%d roots", len(res.Forgotten))},
	}
	if res.KeptCovered > 0 {
		kv = append(kv, [2]string{"kept",
			fmt.Sprintf("%d files another root still covers", res.KeptCovered)})
	}
	kv = append(kv, [2]string{"remaining", fmt.Sprintf("%d roots", len(res.Roots))})
	render.KV(kv)
	for _, p := range res.Forgotten {
		fmt.Printf("  - %s (root)\n", p)
	}
	for _, p := range res.RemovedFiles {
		fmt.Printf("  - %s\n", p)
	}

	// Naming a path with nothing indexed under it isn't an error, but silently
	// doing nothing would look like success — so say which ones missed. Skipped
	// when something was kept: the count is not attributed per path, and "nothing
	// indexed under it" about a file that is indexed would be a lie.
	if res.KeptCovered > 0 {
		return nil
	}
	matched := map[string]bool{}
	for _, p := range res.Forgotten {
		matched[p] = true
	}
	for _, f := range res.RemovedFiles {
		for _, p := range asked {
			if f == p || strings.HasPrefix(f, p+string(filepath.Separator)) {
				matched[p] = true
			}
		}
	}
	for _, p := range asked {
		if !matched[p] {
			fmt.Fprintf(os.Stderr, "  ! nothing indexed under %s\n", p)
		}
	}
	return nil
}

func init() {
	unindexCmd.Flags().BoolVarP(&unindexJSON, "json", "j", false, "emit raw JSON")
	unindexCmd.Flags().BoolVar(&unindexYes, "yes", false,
		"skip the confirmation prompt (e.g. in scripts)")
	unindexCmd.Flags().BoolVar(&unindexDryRun, "dry-run", false,
		"show what would be dropped and forgotten, without touching anything")
}
