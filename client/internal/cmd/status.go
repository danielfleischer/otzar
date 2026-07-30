package cmd

import (
	"fmt"
	"time"

	"github.com/spf13/cobra"

	"otzar/internal/api"
	"otzar/internal/buildinfo"
	"otzar/internal/lifecycle"
	"otzar/internal/render"
)

var statusJSON bool

// serverVersion renders what /health said for humans. A server predating the
// version field reports nothing, which is itself the useful answer: it is old
// enough that this client didn't ship with it.
func serverVersion(v string) string {
	if v == "" {
		return "unknown (server predates version reporting)"
	}
	return v
}

// nullable keeps "the server didn't tell us" out of the JSON as null rather than
// as an empty string a consumer has to know to treat as absent.
func nullable(s string) any {
	if s == "" {
		return nil
	}
	return s
}

// sourceRow renders where the server came from, preferring what the running one
// says over what this client would boot.
//
// The two answer different questions, and only the first is a fact: the
// prediction is resolved from the current working directory, so it flips to the
// checkout the moment you run `status` from one — while the daemon it is
// describing may be an installed jar started weeks ago. When there is no fact to
// report the prediction is still worth having, but it is labelled as one.
func sourceRow(running string, wouldBoot string) string {
	if running != "" {
		return running
	}
	return wouldBoot + " (would boot; not necessarily what is running)"
}

var statusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show server health and index stats",
	RunE: func(cmd *cobra.Command, args []string) error {
		// Which server would we boot — an installed jar or a checkout? The first
		// thing worth knowing when an install misbehaves, and with nothing
		// running it is the only source there is to report.
		wouldBoot := lifecycle.ServerSource()

		if !lifecycle.IsHealthy() {
			if statusJSON {
				return render.JSON(map[string]any{"running": false, "would_boot": wouldBoot})
			}
			render.KV([][2]string{
				{"server", "not running"},
				{"source", wouldBoot},
			})
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
			// source is the running server's own answer (null if it is too old
			// to give one); would_boot is this client's prediction. Keeping them
			// as separate keys is the whole point — they can disagree.
			return render.JSON(map[string]any{
				"running":    true,
				"source":     nullable(health.Source),
				"would_boot": wouldBoot,
				"client":     buildinfo.Version,
				"health":     health,
				"stats":      stats,
			})
		}

		// No row for /health's own "status" field: it can only read "ok" here,
		// since a server that didn't answer took the not-running branch above.
		// The versions earn their lines instead — this command deliberately does
		// not go through ensureServer, so it is the one place a client/server
		// mismatch cannot announce itself, and the pair shown together is what
		// makes it visible.
		render.KV([][2]string{
			{"server", "running"},
			{"source", sourceRow(health.Source, wouldBoot)},
			{"version", serverVersion(health.Version)},
			{"client", buildinfo.Version},
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
