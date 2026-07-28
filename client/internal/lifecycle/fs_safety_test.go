package lifecycle

import (
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// The client never opens a file it was asked to index — it resolves the path to
// absolute and hands the string to the server. Every write it does make belongs
// to otzar's own state under ~/.otzar: this package's dir() and the PortFile /
// PidFile / LogFile helpers over it.
//
// This scans the whole client source rather than one function, so a future
// command that writes somewhere else fails here instead of on a user's disk.
// (rag.fs-safety-test does the same for the server, which is the side that
// actually reads user files.) Test files are exempt — they write to t.TempDir().
var (
	mutatingCall = regexp.MustCompile(
		`\bos\.(Remove|RemoveAll|WriteFile|Create|CreateTemp|Rename|Truncate|Chmod|Chown|Mkdir|MkdirAll|MkdirTemp|OpenFile|Symlink|Link)\(`)
	otzarState = regexp.MustCompile(`\b(dir\(\)|PortFile\(\)|PidFile\(\)|LogFile\(\))`)
)

func TestClientWritesOnlyOtzarState(t *testing.T) {
	var offenders []string
	scanned := 0

	err := filepath.WalkDir("../..", func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}
		b, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		scanned++
		lines := strings.Split(string(b), "\n")
		for i, line := range lines {
			if !mutatingCall.MatchString(line) {
				continue
			}
			// Go binds the path a line or two before the call
			// (`path := filepath.Join(dir(), "start.lock")`), so the state check
			// looks at a small window ending at the call.
			window := strings.Join(lines[max(0, i-3):i+1], "\n")
			if otzarState.MatchString(window) {
				continue
			}
			offenders = append(offenders, fmt.Sprintf("%s:%d  %s", path, i+1, strings.TrimSpace(line)))
		}
		return nil
	})
	if err != nil {
		t.Fatalf("walking the client source: %v", err)
	}
	if scanned == 0 {
		t.Fatal("scanned no source files — the walk root is wrong")
	}
	if len(offenders) > 0 {
		t.Errorf("filesystem write outside ~/.otzar:\n  %s\n"+
			"otzar must never create, modify or delete a user's file: write through the "+
			"~/.otzar helpers in lifecycle.go, or — if this really is otzar's own state — "+
			"add it to otzarState here.", strings.Join(offenders, "\n  "))
	}
}
