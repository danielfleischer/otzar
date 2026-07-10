package cmd

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"myrag/internal/api"
	"myrag/internal/render"
)

var (
	indexRecursive bool
	indexJSON      bool
)

var indexCmd = &cobra.Command{
	Use:   "index <path>...",
	Short: "Index files or folders into the search index",
	Long: `Walk the given files and folders, extract text, chunk it, and add the
chunks to the local index. Directories are only descended with --recursive.`,
	Args: cobra.MinimumNArgs(1),
	RunE: func(cmd *cobra.Command, args []string) error {
		// The server runs in its own working directory, so send absolute paths.
		paths := make([]string, 0, len(args))
		for _, a := range args {
			abs, err := filepath.Abs(a)
			if err != nil {
				return err
			}
			paths = append(paths, abs)
		}

		// Indexing can be slow on a large tree; give it a generous budget.
		c, err := api.NewWithTimeout(10 * time.Minute)
		if err != nil {
			return err
		}

		// Show a live multi-line block on an interactive terminal; stay quiet when
		// piped or emitting JSON so stdout carries only the result.
		bar := newProgressBar(os.Stderr, !indexJSON && isTerminal(os.Stderr))
		res, err := c.IndexStream(api.IndexRequest{Paths: paths, Recursive: indexRecursive}, bar.update)
		bar.finish()
		if err != nil {
			return err
		}

		if indexJSON {
			return render.JSON(res)
		}

		kv := [][2]string{
			{"indexed", fmt.Sprintf("%d files", res.Indexed)},
			{"chunks", fmt.Sprintf("%d", res.Chunks)},
			{"unchanged", fmt.Sprintf("%d files", res.Unchanged)},
			{"skipped", fmt.Sprintf("%d files", res.Skipped)},
			{"errors", fmt.Sprintf("%d", len(res.Errors))},
		}
		if res.Cancelled {
			kv = append(kv, [2]string{"cancelled", "yes (client disconnected)"})
		}
		render.KV(kv)
		for _, e := range res.Errors {
			fmt.Printf("  ! %s: %s\n", e.Path, e.Error)
		}
		return nil
	},
}

// fileProg is one in-flight file's chunk progress.
type fileProg struct {
	name  string
	done  int
	total int
}

// progressBar renders a live, multi-line indexing view — one chunk bar per file
// currently embedding (up to index-concurrency at once), plus a totals footer:
//
//	book.md    [████░░░░] 320/1867
//	report.pdf [██████░░]   88/120
//	files 3/12 · 5,867 chunks
//
// The whole block is redrawn in place with ANSI cursor moves on each event.
// Completed files roll off the top (their chunks fold into the footer). When
// disabled (non-TTY or --json) every method is a no-op.
type progressBar struct {
	w          io.Writer
	enabled    bool
	totalFiles int
	doneFiles  int
	doneChunks int // chunks from files that already finished (footer accumulator)
	active     []string
	byPath     map[string]*fileProg
	prevLines  int
	drawn      bool
}

func newProgressBar(w io.Writer, enabled bool) *progressBar {
	return &progressBar{w: w, enabled: enabled, byPath: map[string]*fileProg{}}
}

// update consumes one stream event and redraws the block.
func (p *progressBar) update(ev api.IndexEvent) {
	if !p.enabled {
		return
	}
	switch ev.Type {
	case "start":
		p.totalFiles = ev.Total
	case "file-start":
		if _, ok := p.byPath[ev.Path]; !ok {
			p.active = append(p.active, ev.Path)
			p.byPath[ev.Path] = &fileProg{name: filepath.Base(ev.Path), total: ev.Total}
		}
	case "chunk":
		if fp := p.byPath[ev.Path]; fp != nil {
			fp.done, fp.total = ev.Done, ev.Total
		}
	case "file":
		p.doneFiles = ev.Done
		if fp := p.byPath[ev.Path]; fp != nil { // nil for unchanged/skipped (never embedded)
			p.doneChunks += fp.done
			delete(p.byPath, ev.Path)
			p.active = removePath(p.active, ev.Path)
		}
	default:
		return
	}
	p.render()
}

const (
	barWidth = 16
	maxName  = 28
)

func (p *progressBar) render() {
	var b strings.Builder
	// Rewind to the top of the previous block and erase downward, so a shrinking
	// block (a file just finished) leaves no orphaned lines behind.
	if p.prevLines > 0 {
		fmt.Fprintf(&b, "\033[%dF", p.prevLines)
	}
	b.WriteString("\033[J")

	nameW := 0
	for _, path := range p.active {
		if n := len([]rune(p.byPath[path].name)); n > nameW {
			nameW = n
		}
	}
	if nameW > maxName {
		nameW = maxName
	}

	chunks := p.doneChunks
	lines := 0
	for _, path := range p.active {
		fp := p.byPath[path]
		chunks += fp.done
		fmt.Fprintf(&b, "%-*s [%s] %d/%d\n",
			nameW, truncName(fp.name, maxName), cells(fp.done, fp.total), fp.done, fp.total)
		lines++
	}
	fmt.Fprintf(&b, "files %d/%d · %s chunks\n", p.doneFiles, p.totalFiles, commas(chunks))
	lines++

	p.prevLines = lines
	fmt.Fprint(p.w, b.String())
	p.drawn = true
}

// finish erases the whole progress block so the caller's summary starts clean.
func (p *progressBar) finish() {
	if !p.enabled || !p.drawn {
		return
	}
	if p.prevLines > 0 {
		fmt.Fprintf(p.w, "\033[%dF\033[J", p.prevLines)
	}
}

// cells renders a done/total ratio as a fixed-width block bar.
func cells(done, total int) string {
	filled := 0
	if total > 0 {
		filled = done * barWidth / total
	}
	if filled > barWidth {
		filled = barWidth
	}
	return strings.Repeat("█", filled) + strings.Repeat("░", barWidth-filled)
}

func truncName(name string, max int) string {
	r := []rune(name)
	if len(r) <= max {
		return name
	}
	return string(r[:max-1]) + "…"
}

func removePath(s []string, path string) []string {
	for i, v := range s {
		if v == path {
			return append(s[:i], s[i+1:]...)
		}
	}
	return s
}

// commas formats a non-negative int with thousands separators: 1240 -> "1,240".
func commas(n int) string {
	s := strconv.Itoa(n)
	if len(s) <= 3 {
		return s
	}
	var b strings.Builder
	pre := len(s) % 3
	if pre > 0 {
		b.WriteString(s[:pre])
	}
	for i := pre; i < len(s); i += 3 {
		if b.Len() > 0 {
			b.WriteByte(',')
		}
		b.WriteString(s[i : i+3])
	}
	return b.String()
}

// isTerminal reports whether f is an interactive character device (a TTY),
// rather than a pipe or file. Dependency-free stand-in for x/term.
func isTerminal(f *os.File) bool {
	fi, err := f.Stat()
	if err != nil {
		return false
	}
	return fi.Mode()&os.ModeCharDevice != 0
}

func init() {
	indexCmd.Flags().BoolVarP(&indexRecursive, "recursive", "r", false,
		"descend into subdirectories")
	indexCmd.Flags().BoolVarP(&indexJSON, "json", "j", false, "emit raw JSON")
}
