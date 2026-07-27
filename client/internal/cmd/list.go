package cmd

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"

	"otzar/internal/render"
)

var listJSON bool

var listCmd = &cobra.Command{
	Use:     "list",
	Aliases: []string{"ls"},
	Short:   "List the source files currently in the index",
	Long: `Print the absolute path of every file in the index, one per line.

This is the index's own record, not roots.edn: roots.edn remembers which trees
you asked to index (what the watch sweep re-scans), while this is what actually
made it in — so a file skipped as unreadable, or one indexed individually
outside any root, shows up here and not there.

Output is bare paths so it pipes: 'otzar list | wc -l', 'otzar list | fzf'.`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		c, err := client()
		if err != nil {
			return err
		}
		res, err := c.Paths()
		if err != nil {
			return err
		}
		if listJSON {
			return render.JSON(res)
		}
		for _, p := range res.Paths {
			fmt.Println(p)
		}
		// Keep stdout pipe-clean: an empty index says so on stderr, so
		// `otzar list | wc -l` still reads 0 rather than 1.
		if len(res.Paths) == 0 {
			fmt.Fprintln(os.Stderr, "nothing indexed yet — try 'otzar index -r <dir>'")
		}
		return nil
	},
}

func init() {
	listCmd.Flags().BoolVarP(&listJSON, "json", "j", false, "emit raw JSON")
}
