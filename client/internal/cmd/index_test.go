package cmd

import (
	"bytes"
	"strings"
	"testing"

	"myrag/internal/api"
)

// Drives the multi-line bar through two files embedding concurrently, checks that
// each shows its own chunk done/total, that finished files fold into the footer's
// chunk total, and that a `file` event for a never-embedded (unchanged) path just
// advances the file counter without panicking.
func TestProgressBarMultiLine(t *testing.T) {
	var buf bytes.Buffer
	p := newProgressBar(&buf, true)
	p.update(api.IndexEvent{Type: "start", Total: 3})
	p.update(api.IndexEvent{Type: "file-start", Path: "/x/a.md", Total: 100})
	p.update(api.IndexEvent{Type: "chunk", Path: "/x/a.md", Done: 64, Total: 100})
	p.update(api.IndexEvent{Type: "file-start", Path: "/x/b.md", Total: 200})
	p.update(api.IndexEvent{Type: "chunk", Path: "/x/b.md", Done: 200, Total: 200})

	out := buf.String()
	// Both files visible with their own per-file chunk fraction (parallelism).
	if !strings.Contains(out, "a.md") || !strings.Contains(out, "64/100") {
		t.Fatalf("a.md progress missing: %q", out)
	}
	if !strings.Contains(out, "b.md") || !strings.Contains(out, "200/200") {
		t.Fatalf("b.md progress missing: %q", out)
	}
	// Redraws use ANSI cursor-up (\033[<n>F) + erase-down (\033[J).
	if !strings.Contains(out, "\033[J") || !strings.Contains(out, "\033[") {
		t.Fatalf("expected ANSI block redraw codes: %q", out)
	}

	// b finishes; its 200 chunks fold into the footer. a runs to completion next.
	buf.Reset()
	p.update(api.IndexEvent{Type: "file", Done: 1, Path: "/x/b.md"})
	p.update(api.IndexEvent{Type: "chunk", Path: "/x/a.md", Done: 100, Total: 100})
	p.update(api.IndexEvent{Type: "file", Done: 2, Path: "/x/a.md"})
	// c.md was unchanged — never a file-start; must not panic, just bump the count.
	p.update(api.IndexEvent{Type: "file", Done: 3, Path: "/x/c.md"})

	tail := lastBlock(buf.String())
	if !strings.Contains(tail, "files 3/3") {
		t.Fatalf("footer file count wrong: %q", tail)
	}
	if !strings.Contains(tail, "300 chunks") { // 200 (b) + 100 (a)
		t.Fatalf("footer should fold finished files' chunks to 300: %q", tail)
	}
	if strings.Contains(tail, "a.md") || strings.Contains(tail, "b.md") {
		t.Fatalf("finished files should have rolled off: %q", tail)
	}

	// finish clears the block for the caller's summary.
	buf.Reset()
	p.finish()
	if !strings.Contains(buf.String(), "\033[J") {
		t.Fatalf("finish should erase the block: %q", buf.String())
	}

	// Disabled bar is silent.
	var q bytes.Buffer
	d := newProgressBar(&q, false)
	d.update(api.IndexEvent{Type: "file-start", Path: "/x/a.md", Total: 10})
	d.update(api.IndexEvent{Type: "chunk", Path: "/x/a.md", Done: 5, Total: 10})
	d.finish()
	if q.Len() != 0 {
		t.Fatalf("disabled bar should be silent, wrote %q", q.String())
	}
}

// lastBlock returns the final redraw frame (everything after the last cursor-up).
func lastBlock(s string) string {
	if i := strings.LastIndex(s, "\033["); i >= 0 {
		return s[i:]
	}
	return s
}

func TestCells(t *testing.T) {
	if got := cells(100, 100); strings.Count(got, "█") != barWidth || strings.Contains(got, "░") {
		t.Errorf("full ratio should be a solid bar: %q", got)
	}
	if got := cells(0, 100); strings.Count(got, "░") != barWidth || strings.Contains(got, "█") {
		t.Errorf("zero ratio should be empty: %q", got)
	}
	if got := cells(5, 0); strings.Count(got, "░") != barWidth { // total 0 → no divide-by-zero
		t.Errorf("total 0 should be empty, got %q", got)
	}
}

func TestCommas(t *testing.T) {
	cases := map[int]string{0: "0", 42: "42", 999: "999", 1000: "1,000", 1240: "1,240", 1234567: "1,234,567"}
	for in, want := range cases {
		if got := commas(in); got != want {
			t.Errorf("commas(%d) = %q, want %q", in, got, want)
		}
	}
}
