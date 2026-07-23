package cmd

import (
	"fmt"
	"time"

	"github.com/spf13/cobra"

	"otzer/internal/lifecycle"
)

var startTimeout time.Duration

var startCmd = &cobra.Command{
	Use:   "start",
	Short: "Boot the server (background JVM process)",
	RunE: func(cmd *cobra.Command, args []string) error {
		if err := lifecycle.Start(startTimeout); err != nil {
			return err
		}
		base, _ := lifecycle.BaseURL()
		fmt.Printf("server started at %s\n", base)
		return nil
	},
}

func init() {
	startCmd.Flags().DurationVar(&startTimeout, "timeout", 60*time.Second,
		"how long to wait for the server to become healthy")
}
