package cmd

import (
	"fmt"
	"time"

	"github.com/spf13/cobra"

	"otzar/internal/lifecycle"
)

var restartTimeout time.Duration

var restartCmd = &cobra.Command{
	Use:   "restart",
	Short: "Stop the server if it's running, then start it again",
	Long: `Pick up server changes without the stop/start dance.

From a checkout that means the Clojure source you just edited; from an installed
jar it means whatever 'make uber' last packaged — a source edit alone won't show
up there until you repackage.

Starting when nothing was running is not an error, so this is safe to run
blind.`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		// Stop reports an error when there was nothing to stop; for a restart
		// that's the normal cold case, not a failure.
		if err := lifecycle.Stop(10 * time.Second); err != nil {
			fmt.Println("server was not running")
		} else {
			fmt.Println("server stopped")
		}
		if err := lifecycle.Start(restartTimeout); err != nil {
			return err
		}
		base, _ := lifecycle.BaseURL()
		fmt.Printf("server started at %s\n", base)
		return nil
	},
}

func init() {
	restartCmd.Flags().DurationVar(&restartTimeout, "timeout", 60*time.Second,
		"how long to wait for the server to become healthy")
}
