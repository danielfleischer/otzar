package cmd

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/spf13/cobra"

	"otzar/internal/api"
	"otzar/internal/render"
)

var (
	indexRecursive bool
	indexJSON      bool
	indexForce     bool
	indexNow       bool
)

var indexCmd = &cobra.Command{
	Use:   "index [path]... | --now",
	Short: "Index files or folders into the search index",
	Long: `Walk the given files and folders, extract text, chunk it, and add the
chunks to the local index. Directories are only descended with --recursive.

With --now and no paths, re-scan everything already indexed: otzar re-runs the
index over the trees you named before, picking up files that are new, changed, or
deleted since. That is the sweep 'otzar server watch' does on a timer, on demand.
Each tree keeps the --recursive setting it was indexed with.`,
	Args: cobra.ArbitraryArgs,
	RunE: func(cmd *cobra.Command, args []string) error {
		if indexNow && len(args) > 0 {
			return fmt.Errorf("--now re-scans what is already indexed, so it takes no paths")
		}
		if !indexNow && len(args) == 0 {
			return fmt.Errorf("name at least one path to index, or pass --now to re-scan what already is")
		}

		// No total budget to set: IndexStream is bounded by silence, not duration,
		// so a run of any length is fine as long as the server keeps talking.
		c, err := client()
		if err != nil {
			return err
		}

		if indexNow {
			return indexKnownRoots(c)
		}

		// The server runs in its own working directory, so send absolute paths.
		paths := make([]string, 0, len(args))
		for _, a := range args {
			abs, err := filepath.Abs(a)
			if err != nil {
				return err
			}
			paths = append(paths, abs)
		}

		// Show a live multi-line block on an interactive terminal; stay quiet when
		// piped or emitting JSON so stdout carries only the result.
		bar := newProgressBar(os.Stderr, !indexJSON && isTerminal(os.Stderr))
		res, err := c.IndexStream(api.IndexRequest{Paths: paths, Recursive: indexRecursive, Force: indexForce}, bar.update)
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
		// Only when it happened: dropping stale chunks is rare, and a permanent
		// "removed 0 files" line would just be noise on every ordinary run.
		if res.Removed > 0 {
			kv = append(kv, [2]string{"removed", fmt.Sprintf("%d files no longer readable", res.Removed)})
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

// indexKnownRoots is `otzar index --now`: fetch the trees already indexed and
// post them straight back to /index. The reconciliation is the server's ordinary
// incremental index — unchanged files cost a stat, changed ones re-embed — so
// this needs no endpoint of its own, exactly like `otzar admin reindex` composing
// /paths with /index.
//
// Deleted files are the one thing an index run can't notice (it walks what is
// there, not what is missing), so this prunes too — after indexing, so a moved
// file's new path can still find its old chunks and reuse their vectors instead
// of re-embedding — but only when every root is still on disk. A vanished root
// (an unmounted volume) makes its whole subtree look orphaned, and dropping it
// would be a mass deletion the user never asked for. That's the same guard the server-side watch sweep applies.
func indexKnownRoots(c *api.Client) error {
	roots, err := c.Roots()
	if err != nil {
		return err
	}
	if len(roots.Roots) == 0 {
		fmt.Println("nothing indexed yet — name a tree first, e.g. `otzar index -r ~/notes`")
		return nil
	}

	var recursive, flat []string
	missing := 0
	for _, r := range roots.Roots {
		if _, err := os.Stat(r.Path); err != nil {
			missing++
			continue
		}
		if r.Recursive {
			recursive = append(recursive, r.Path)
		} else {
			flat = append(flat, r.Path)
		}
	}

	// One stream per recursive flag; the bar accumulates across both.
	bar := newProgressBar(os.Stderr, !indexJSON && isTerminal(os.Stderr))
	total := api.IndexResult{}
	for _, run := range []struct {
		paths     []string
		recursive bool
	}{{recursive, true}, {flat, false}} {
		if len(run.paths) == 0 {
			continue
		}
		res, err := c.IndexStream(api.IndexRequest{Paths: run.paths, Recursive: run.recursive, Force: indexForce}, bar.update)
		if err != nil {
			bar.finish()
			return err
		}
		total.Indexed += res.Indexed
		total.Chunks += res.Chunks
		total.Unchanged += res.Unchanged
		total.Skipped += res.Skipped
		total.Removed += res.Removed
		total.Errors = append(total.Errors, res.Errors...)
		total.Cancelled = total.Cancelled || res.Cancelled
	}
	bar.finish()

	pruned := 0
	if missing == 0 {
		p, err := c.Prune(false)
		if err != nil {
			return err
		}
		pruned = len(p.Removed)
	}

	if indexJSON {
		return render.JSON(total)
	}
	kv := [][2]string{
		{"roots", fmt.Sprintf("%d", len(roots.Roots))},
		{"indexed", fmt.Sprintf("%d files", total.Indexed)},
		{"chunks", fmt.Sprintf("%d", total.Chunks)},
		{"unchanged", fmt.Sprintf("%d files", total.Unchanged)},
		{"skipped", fmt.Sprintf("%d files", total.Skipped)},
		{"pruned", fmt.Sprintf("%d gone from disk", pruned)},
		{"errors", fmt.Sprintf("%d", len(total.Errors))},
	}
	if total.Removed > 0 {
		kv = append(kv, [2]string{"removed", fmt.Sprintf("%d files no longer readable", total.Removed)})
	}
	render.KV(kv)
	if missing > 0 {
		fmt.Printf("  ! %d root(s) not on disk — skipped, and nothing was pruned this run\n", missing)
	}
	for _, e := range total.Errors {
		fmt.Printf("  ! %s: %s\n", e.Path, e.Error)
	}
	return nil
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
	baseFiles  int // files finished in earlier runs (`--now` streams up to two)
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
		// `index --now` streams the recursive and non-recursive roots as two runs,
		// so `start` can arrive twice with its own total and a file count that
		// restarts at 1. Accumulate, or the footer walks backwards mid-scan.
		p.totalFiles += ev.Total
		p.baseFiles = p.doneFiles
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
		p.doneFiles = p.baseFiles + ev.Done
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
	indexCmd.Flags().BoolVarP(&indexForce, "force", "f", false,
		"re-embed every file even if unchanged (needed after a chunker or schema change)")
	indexCmd.Flags().BoolVar(&indexNow, "now", false,
		"re-scan everything already indexed, instead of naming paths")
}
