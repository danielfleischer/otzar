package cmd

import "github.com/spf13/cobra"

// adminCmd groups index-maintenance verbs — operations that reconcile or rebuild
// the index rather than read or add to it. It has no run of its own; invoking it
// bare prints the subcommand help.
var adminCmd = &cobra.Command{
	Use:   "admin",
	Short: "Index maintenance (stats, prune, clear, reindex)",
	Long: `Maintenance operations on the index itself: report its size, reconcile it
against disk, clear it, and rebuild it after a chunker/model change. These are run
occasionally, apart from the everyday index/query verbs.`,
}

func init() {
	adminCmd.AddCommand(statsCmd, pruneCmd, clearCmd, reindexCmd)
}
