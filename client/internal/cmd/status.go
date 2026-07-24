package cmd

import (
	"fmt"
	"time"

	"github.com/spf13/cobra"

	"otzar/internal/api"
	"otzar/internal/lifecycle"
	"otzar/internal/render"
)

var statusJSON bool

var statusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show server health and index stats",
	RunE: func(cmd *cobra.Command, args []string) error {
		if !lifecycle.IsHealthy() {
			if statusJSON {
				return render.JSON(map[string]any{"running": false})
			}
			fmt.Println("server: not running")
			return nil
		}

		c, err := api.New()
		if err != nil {
			return err
		}
		health, err := c.Health()
		if err != nil {
			return err
		}
		stats, err := c.Stats()
		if err != nil {
			return err
		}

		if statusJSON {
			return render.JSON(map[string]any{
				"running": true,
				"health":  health,
				"stats":   stats,
			})
		}

		render.KV([][2]string{
			{"server", "running"},
			{"status", health.Status},
			{"uptime", time.Duration(health.UptimeS * float64(time.Second)).Round(time.Second).String()},
			{"docs", fmt.Sprintf("%d", stats.NumDocs)},
			{"chunks", fmt.Sprintf("%d", stats.NumChunks)},
		})
		return nil
	},
}

func init() {
	statusCmd.Flags().BoolVar(&statusJSON, "json", false, "emit raw JSON")
}
