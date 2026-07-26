package cmd

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
)

// version is stamped in at build time via -ldflags (-X
// otzar/internal/cmd.version=...); see the Makefile. A plain `go build` reports
// "dev".
var version = "dev"

var rootCmd = &cobra.Command{
	Use:     "otzar",
	Version: version,
	Short:   "Hybrid local document search (vector + keyword + metadata)",
	Long: `otzar is the CLI for a personal hybrid retrieval system.

It controls the lifecycle of a local Clojure/Lucene server and queries it over
HTTP. The same binary also speaks MCP (` + "`otzar mcp`" + `) so LLM hosts can search
your index as a tool.`,
	SilenceUsage:  true,
	SilenceErrors: true,
}

// Execute runs the root command and maps errors to a non-zero exit code.
func Execute() {
	if err := rootCmd.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func init() {
	rootCmd.AddCommand(serverCmd, adminCmd, indexCmd, listCmd, queryCmd, mcpCmd)
}
