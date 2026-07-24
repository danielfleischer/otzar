package cmd

import "github.com/spf13/cobra"

// serverCmd groups the server-process lifecycle verbs. It has no run of its own;
// invoking it bare prints the subcommand help.
var serverCmd = &cobra.Command{
	Use:   "server",
	Short: "Manage the server process (start, stop, status, watch)",
	Long: `Control the local Clojure/Lucene server process that holds the index and
answers queries. Start it before indexing or searching; stop it to reclaim the
JVM. 'server watch' toggles the background auto-reindex sweep.`,
}

func init() {
	serverCmd.AddCommand(startCmd, stopCmd, statusCmd, watchCmd)
}
