package cmd

import (
	"fmt"
	"time"

	"github.com/spf13/cobra"

	"otzar/internal/lifecycle"
)

var stopCmd = &cobra.Command{
	Use:   "stop",
	Short: "Stop the server (POST /shutdown, then verify exit)",
	RunE: func(cmd *cobra.Command, args []string) error {
		if err := lifecycle.Stop(10 * time.Second); err != nil {
			return err
		}
		fmt.Println("server stopped")
		return nil
	},
}
