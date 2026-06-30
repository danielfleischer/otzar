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
