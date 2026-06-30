package cmd

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
)

var rootCmd = &cobra.Command{
	Use:           "myrag",
	Short:         "Hybrid local document search (vector + keyword + metadata)",
	Long: `myrag is the CLI for a personal hybrid retrieval system.

It controls the lifecycle of a local Clojure/Lucene server and queries it over
HTTP. The same binary also speaks MCP (` + "`myrag mcp`" + `) so LLM hosts can search
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
	rootCmd.AddCommand(startCmd, stopCmd, statusCmd)
}
