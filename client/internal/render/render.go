// Package render handles output: a human-friendly default and a --json mode for
// scripting/piping.
package render

import (
	"encoding/json"
	"fmt"
	"os"
)

// JSON pretty-prints any value to stdout. Used when --json is set.
func JSON(v any) error {
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	return enc.Encode(v)
}

// KV prints aligned key/value lines for human-readable command output.
func KV(pairs [][2]string) {
	width := 0
	for _, p := range pairs {
		if len(p[0]) > width {
			width = len(p[0])
		}
	}
	for _, p := range pairs {
		fmt.Printf("%-*s  %s\n", width, p[0], p[1])
	}
}

// Table prints rows under headers, each column padded to its widest cell. The
// last column is left unpadded so trailing free-text (snippets) isn't stretched.
func Table(headers []string, rows [][]string) {
	if len(headers) == 0 {
		return
	}
	widths := make([]int, len(headers))
	for i, h := range headers {
		widths[i] = len(h)
	}
	for _, r := range rows {
		for i, c := range r {
			if i < len(widths) && len(c) > widths[i] {
				widths[i] = len(c)
			}
		}
	}
	printRow(widths, headers)
	for _, r := range rows {
		printRow(widths, r)
	}
}

func printRow(widths []int, cells []string) {
	last := len(cells) - 1
	for i, c := range cells {
		if i == last {
			fmt.Print(c)
		} else {
			fmt.Printf("%-*s  ", widths[i], c)
		}
	}
	fmt.Println()
}
