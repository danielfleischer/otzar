package cmd

import (
	"context"
	"errors"
	"os/signal"
	"syscall"
	"time"

	"github.com/spf13/cobra"

	"myrag/internal/api"
	"myrag/internal/lifecycle"
	"myrag/internal/mcp"
)

var mcpCmd = &cobra.Command{
	Use:   "mcp",
	Short: "Speak MCP over stdio (launched by an LLM host)",
	Long: `Run an MCP server on stdin/stdout exposing read-only tools over the local
index: search, get_document, and list_indexed_paths. An LLM host (Claude
Desktop/Code) launches this as a subprocess and drives it over stdio — it is not
meant to be run interactively.

The backing myrag server is started automatically if it isn't already running,
and is left running when the host disconnects (stop it explicitly with
'myrag stop'), since the CLI or another MCP session may still be using it.`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		// Lazily ensure the backing server is up (§13.4). Start serializes across
		// processes and returns ErrAlreadyRunning when one is already healthy —
		// for us that's success, not a failure. stdout is the JSON-RPC channel, so
		// lifecycle logging must not go there — Start logs to a file. 60s matches
		// `myrag start`: a cold JVM boot (Lucene + cider-nrepl) can take a while,
		// and the host is still waiting on us for the handshake.
		if err := lifecycle.Start(60 * time.Second); err != nil && !errors.Is(err, lifecycle.ErrAlreadyRunning) {
			return err
		}
		c, err := api.New()
		if err != nil {
			return err
		}
		// Serve until stdin EOF (host disconnect) or a termination signal.
		ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
		defer stop()
		return mcp.Run(ctx, c)
	},
}
