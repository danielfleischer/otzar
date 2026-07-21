package cmd

import (
	"fmt"

	"github.com/spf13/cobra"

	"myrag/internal/api"
	"myrag/internal/render"
)

var statsJSON bool

var statsCmd = &cobra.Command{
	Use:   "stats",
	Short: "Show index size (documents and chunks)",
	Long: `Report how much is in the index: the number of distinct source files (docs)
and the total number of chunks across them. Unlike 'server status' this is just
the index counts, with no server-health line.`,
	Args: cobra.NoArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		c, err := api.New()
		if err != nil {
			return err
		}
		s, err := c.Stats()
		if err != nil {
			return err
		}
		if statsJSON {
			return render.JSON(s)
		}
		render.KV([][2]string{
			{"docs", fmt.Sprintf("%d", s.NumDocs)},
			{"chunks", fmt.Sprintf("%d", s.NumChunks)},
		})
		return nil
	},
}

func init() {
	statsCmd.Flags().BoolVarP(&statsJSON, "json", "j", false, "emit raw JSON")
}
