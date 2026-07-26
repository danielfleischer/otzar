package cmd

import (
	"fmt"
	"time"

	"github.com/spf13/cobra"

	"otzar/internal/api"
	"otzar/internal/render"
)

var (
	watchJSON     bool
	watchInterval time.Duration
)

// watchCmd groups the background auto-reindex toggle under `server watch`. It has
// no run of its own; invoking it bare prints the subcommand help.
var watchCmd = &cobra.Command{
	Use:   "watch",
	Short: "Background auto-reindex of your indexed roots (enable, disable, status)",
	Long: `Periodically re-scan everything you've indexed and pick up new, changed, and
deleted files automatically — no manual re-index. The sweep runs inside the server
on an interval (default 30m); scanning an unchanged tree is cheap and does not load
the embedding model, so only real changes cost anything. Off by default; the
setting persists across server restarts.`,
}

// printWatch renders a WatchStatus as aligned key/value lines.
func printWatch(w *api.WatchStatus) {
	state := "disabled"
	if w.Enabled {
		state = "enabled"
	}
	kv := [][2]string{
		{"watch", state},
		{"interval", (time.Duration(w.IntervalS) * time.Second).String()},
	}
	if w.Enabled {
		next := "due now"
		if d := time.Duration(w.NextSweepInS) * time.Second; d > 0 {
			next = "in " + d.String()
		}
		kv = append(kv, [2]string{"next sweep", next})
	}
	if w.Indexing {
		kv = append(kv, [2]string{"indexing", "yes (a run is in progress)"})
	}
	if w.LastSweepAt > 0 {
		kv = append(kv, [2]string{"last sweep", time.UnixMilli(w.LastSweepAt).Format(time.RFC1123)})
		if r := w.LastResult; r != nil {
			switch {
			case r.Error != "":
				kv = append(kv, [2]string{"last result", "error: " + r.Error})
			case r.Skipped:
				kv = append(kv, [2]string{"last result", "skipped (a run was already in progress)"})
			default:
				kv = append(kv, [2]string{"last result",
					fmt.Sprintf("%d indexed · %d unchanged · %d pruned · %d errors",
						r.Indexed, r.Unchanged, len(r.Pruned), len(r.Errors))})
			}
		}
	}
	render.KV(kv)
}

var watchStatusCmd = &cobra.Command{
	Use:   "status",
	Short: "Show whether the sweep is armed and its last run",
	Args:  cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		c, err := api.New()
		if err != nil {
			return err
		}
		w, err := c.Watch()
		if err != nil {
			return err
		}
		if watchJSON {
			return render.JSON(w)
		}
		printWatch(w)
		return nil
	},
}

var watchEnableCmd = &cobra.Command{
	Use:   "enable",
	Short: "Arm the background sweep (optionally --interval)",
	Long: `Turn on the background auto-reindex. The first sweep runs one interval from now,
not immediately, so enabling never itself kicks off embedding work. Pass --interval
to change the cadence (e.g. 30m, 1h); omit it to keep the current setting.`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		c, err := api.New()
		if err != nil {
			return err
		}
		intervalS := 0
		if watchInterval > 0 {
			intervalS = int(watchInterval / time.Second)
		}
		w, err := c.SetWatch(true, intervalS)
		if err != nil {
			return err
		}
		if watchJSON {
			return render.JSON(w)
		}
		printWatch(w)
		return nil
	},
}

var watchDisableCmd = &cobra.Command{
	Use:   "disable",
	Short: "Turn the background sweep off",
	Args:  cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		c, err := api.New()
		if err != nil {
			return err
		}
		w, err := c.SetWatch(false, 0)
		if err != nil {
			return err
		}
		if watchJSON {
			return render.JSON(w)
		}
		printWatch(w)
		return nil
	},
}

func init() {
	watchEnableCmd.Flags().DurationVar(&watchInterval, "interval", 0,
		"sweep interval (e.g. 30m, 1h); default keeps the current setting")
	for _, c := range []*cobra.Command{watchStatusCmd, watchEnableCmd, watchDisableCmd} {
		c.Flags().BoolVar(&watchJSON, "json", false, "emit raw JSON")
	}
	watchCmd.AddCommand(watchStatusCmd, watchEnableCmd, watchDisableCmd)
}
