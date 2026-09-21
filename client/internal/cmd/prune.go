package cmd

import (
	"fmt"

	"github.com/spf13/cobra"

	"otzar/internal/render"
)

var (
	pruneDryRun bool
	pruneJSON   bool
)

var pruneCmd = &cobra.Command{
	Use:   "prune",
	Short: "Drop indexed files that no longer exist on disk (orphan cleanup)",
	Long: `Reconcile the index against disk. A source file that was indexed but has since
been deleted or renamed leaves orphaned chunks behind; prune finds those files
and removes their chunks. A rename shows up as an orphan at the old path — index
the new location to pick it up again.

Use --dry-run to list the orphans without deleting anything.`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		c, err := maintenanceClient()
		if err != nil {
			return err
		}
		res, err := c.Prune(pruneDryRun)
		if err != nil {
			return err
		}

		if pruneJSON {
			return render.JSON(res)
		}

		verb := "removed"
		if res.DryRun {
			verb = "would remove"
		}
		render.KV([][2]string{
			{verb, fmt.Sprintf("%d files", len(res.Removed))},
			{"kept", fmt.Sprintf("%d files", res.Kept)},
		})
		for _, p := range res.Removed {
			fmt.Printf("  - %s\n", p)
		}
		return nil
	},
}

func init() {
	pruneCmd.Flags().BoolVar(&pruneDryRun, "dry-run", false,
		"list orphaned files without deleting them")
	pruneCmd.Flags().BoolVarP(&pruneJSON, "json", "j", false, "emit raw JSON")
}
