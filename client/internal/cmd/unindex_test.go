package cmd

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"otzar/internal/api"
)

// rootsServer is a fake server answering /health and DELETE /roots. It records
// the dry_run flag of every DELETE, in order, so a test can prove which requests
// the command actually sent — the property that matters for a verb that deletes:
// a preview must never be the deletion, and a refusal must never reach the server.
type rootsServer struct {
	mu      sync.Mutex
	dryRuns []bool
	preview string        // body for a dry-run request
	real    string        // body for a real request
	delay   time.Duration // how long a real request takes to answer
}

func (s *rootsServer) sent() []bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]bool(nil), s.dryRuns...)
}

// stageRoots starts s and publishes its port the way stageServer does, so
// client() and maintenanceClient() both find it without booting anything.
func stageRoots(t *testing.T, s *rootsServer) {
	t.Helper()
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/health":
			w.WriteHeader(http.StatusOK)
		case r.URL.Path == "/prune" && r.Method == http.MethodPost:
			time.Sleep(s.delay) // a real prune reclaims too
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"removed":[],"kept":0}`))
		case r.URL.Path == "/roots" && r.Method == http.MethodDelete:
			var req api.RemoveRootsRequest
			_ = json.NewDecoder(r.Body).Decode(&req)
			s.mu.Lock()
			s.dryRuns = append(s.dryRuns, req.DryRun)
			s.mu.Unlock()
			body := s.real
			if req.DryRun {
				body = s.preview
			} else if s.delay > 0 {
				time.Sleep(s.delay)
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(body))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(ts.Close)

	u, err := url.Parse(ts.URL)
	if err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(os.Getenv("HOME"), ".otzar")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "server.port"), []byte(u.Port()), 0o644); err != nil {
		t.Fatal(err)
	}
}

// unindexFlags sets the command's package-level flags for one test and restores
// them afterwards, so tests don't leak state into each other.
func unindexFlags(t *testing.T, dryRun, yes bool) {
	t.Helper()
	unindexDryRun, unindexYes, unindexJSON = dryRun, yes, false
	t.Cleanup(func() { unindexDryRun, unindexYes, unindexJSON = false, false, false })
}

// nonTerminalStdin swaps stdin for a pipe, which confirm() correctly treats as
// non-interactive. (/dev/null would not do: it is a character device, so it
// reads as a terminal.)
func nonTerminalStdin(t *testing.T) {
	t.Helper()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	w.Close()
	old := os.Stdin
	os.Stdin = r
	t.Cleanup(func() { os.Stdin = old; r.Close() })
}

// capture runs f with stdout and stderr redirected, returning what each received.
func capture(t *testing.T, f func() error) (stdout, stderr string, err error) {
	t.Helper()
	oldOut, oldErr := os.Stdout, os.Stderr
	outR, outW, _ := os.Pipe()
	errR, errW, _ := os.Pipe()
	os.Stdout, os.Stderr = outW, errW

	outC, errC := make(chan string), make(chan string)
	go func() { b, _ := io.ReadAll(outR); outC <- string(b) }()
	go func() { b, _ := io.ReadAll(errR); errC <- string(b) }()

	err = f()
	outW.Close()
	errW.Close()
	os.Stdout, os.Stderr = oldOut, oldErr
	return <-outC, <-errC, err
}

func runUnindex(t *testing.T, args ...string) (string, string, error) {
	t.Helper()
	return capture(t, func() error { return unindexCmd.RunE(unindexCmd, args) })
}

const dropsTwo = `{"forgotten":["/t"],"roots":[],"removed_files":["/t/a.md","/t/b.md"],` +
	`"removed_chunks":5,"kept_covered":0%s}`

func body(dry bool) string {
	if dry {
		return strings.Replace(dropsTwo, "%s", `,"dry_run":true`, 1)
	}
	return strings.Replace(dropsTwo, "%s", "", 1)
}

func sameBools(a, b []bool) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// ── which requests reach the server ─────────────────────────────────────────

func TestUnindexDryRunNeverSendsTheDeletion(t *testing.T) {
	isolate(t)
	s := &rootsServer{preview: body(true), real: body(false)}
	stageRoots(t, s)
	unindexFlags(t, true, false)

	out, _, err := runUnindex(t, "/t")
	if err != nil {
		t.Fatalf("unindex --dry-run: %v", err)
	}
	if got := s.sent(); !sameBools(got, []bool{true}) {
		t.Errorf("requests sent (dry_run flags) = %v, want exactly one preview [true]", got)
	}
	if !strings.Contains(out, "would drop") || !strings.Contains(out, "2 files (5 chunks)") {
		t.Errorf("dry run should say what would go:\n%s", out)
	}
}

func TestUnindexYesSkipsThePreview(t *testing.T) {
	isolate(t)
	s := &rootsServer{preview: body(true), real: body(false)}
	stageRoots(t, s)
	unindexFlags(t, false, true)

	out, _, err := runUnindex(t, "/t")
	if err != nil {
		t.Fatalf("unindex --yes: %v", err)
	}
	if got := s.sent(); !sameBools(got, []bool{false}) {
		t.Errorf("requests sent = %v, want exactly one real deletion [false]", got)
	}
	if !strings.Contains(out, "dropped") || strings.Contains(out, "would") {
		t.Errorf("a real run reports in the past tense:\n%s", out)
	}
}

// The gate that matters most: a script without --yes must not delete. The
// preview is harmless and is sent; the deletion is not.
func TestUnindexWithoutYesRefusesWhenItCannotAsk(t *testing.T) {
	isolate(t)
	nonTerminalStdin(t)
	s := &rootsServer{preview: body(true), real: body(false)}
	stageRoots(t, s)
	unindexFlags(t, false, false)

	_, _, err := runUnindex(t, "/t")
	if err == nil || !strings.Contains(err.Error(), "--yes") {
		t.Fatalf("want a refusal pointing at --yes, got %v", err)
	}
	if got := s.sent(); !sameBools(got, []bool{true}) {
		t.Errorf("requests sent = %v, want only the preview [true] — nothing real", got)
	}
}

// Forgetting a root with nothing indexed under it deletes no content, so there is
// nothing to confirm: it must go through even where a prompt is impossible.
func TestUnindexForgetsARootWithoutPromptingWhenNothingWouldBeDropped(t *testing.T) {
	isolate(t)
	nonTerminalStdin(t)
	onlyRoot := `{"forgotten":["/t"],"roots":[],"removed_files":[],"removed_chunks":0,"kept_covered":0%s}`
	s := &rootsServer{
		preview: strings.Replace(onlyRoot, "%s", `,"dry_run":true`, 1),
		real:    strings.Replace(onlyRoot, "%s", "", 1),
	}
	stageRoots(t, s)
	unindexFlags(t, false, false)

	if _, _, err := runUnindex(t, "/t"); err != nil {
		t.Fatalf("unindex: %v", err)
	}
	if got := s.sent(); !sameBools(got, []bool{true, false}) {
		t.Errorf("requests sent = %v, want preview then the real call [true false]", got)
	}
}

// Nothing to forget and nothing to drop: report it and stop after the preview.
func TestUnindexStopsAfterAnEmptyPreview(t *testing.T) {
	isolate(t)
	nonTerminalStdin(t)
	empty := `{"forgotten":[],"roots":[],"removed_files":[],"removed_chunks":0,"kept_covered":0,"dry_run":true}`
	s := &rootsServer{preview: empty, real: empty}
	stageRoots(t, s)
	unindexFlags(t, false, false)

	_, stderr, err := runUnindex(t, "/nowhere")
	if err != nil {
		t.Fatalf("unindex: %v", err)
	}
	if got := s.sent(); !sameBools(got, []bool{true}) {
		t.Errorf("requests sent = %v, want only the preview [true]", got)
	}
	if !strings.Contains(stderr, "nothing indexed under /nowhere") {
		t.Errorf("a path that matched nothing should say so, got stderr %q", stderr)
	}
}

// ── what the report says ────────────────────────────────────────────────────

// The regression from the first version: files spared because another root still
// covers them were reported as "nothing indexed under it" — false, and the one
// message that would send a user hunting for a bug that isn't there.
func TestReportSaysKeptRatherThanNothingIndexed(t *testing.T) {
	unindexJSON = false
	res := &api.RemoveRootsResult{KeptCovered: 3}
	out, stderr, err := capture(t, func() error { return reportUnindexed(res, []string{"/t/covered"}) })
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "3 files another root still covers") {
		t.Errorf("want the kept count reported:\n%s", out)
	}
	if strings.Contains(stderr, "nothing indexed") {
		t.Errorf("must not claim nothing is indexed when files were kept: %q", stderr)
	}
}

// A path counts as matched when a dropped file is beneath it — and only then. The
// separator matters: /t/notes-old/x.md is not under /t/notes.
func TestReportAttributesDroppedFilesToTheirPath(t *testing.T) {
	unindexJSON = false
	res := &api.RemoveRootsResult{RemovedFiles: []string{"/t/notes-old/x.md"}}
	_, stderr, err := capture(t, func() error {
		return reportUnindexed(res, []string{"/t/notes", "/t/notes-old"})
	})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(stderr, "nothing indexed under /t/notes\n") {
		t.Errorf("/t/notes matched nothing and should say so; stderr %q", stderr)
	}
	if strings.Contains(stderr, "/t/notes-old") {
		t.Errorf("/t/notes-old dropped a file and must not be reported as a miss; stderr %q", stderr)
	}
}

// ── the deadline ────────────────────────────────────────────────────────────

// The bug a user hit: a real unindex reclaims deleted documents, which on a real
// index outlasts the default budget — the server finished, the client reported
// "context deadline exceeded". Shrink the default so the difference shows in
// milliseconds: the plain client gives up, the maintenance client waits.
func TestMaintenanceClientOutlastsTheDefaultDeadline(t *testing.T) {
	isolate(t)
	s := &rootsServer{real: body(false), delay: 200 * time.Millisecond}
	stageRoots(t, s)

	old := api.DefaultTimeout
	api.DefaultTimeout = 50 * time.Millisecond
	t.Cleanup(func() { api.DefaultTimeout = old })

	plain, err := client()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := plain.RemoveRoots([]string{"/t"}, false); err == nil ||
		!strings.Contains(err.Error(), "deadline exceeded") {
		t.Fatalf("control: the default client should time out here, got %v", err)
	}

	m, err := maintenanceClient()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.RemoveRoots([]string{"/t"}, false); err != nil {
		t.Errorf("maintenance client gave up on a slow reclaim: %v", err)
	}
}

// And the commands that reclaim must actually use it — the fix is one word in
// each RunE, and one word is easy to revert.
func TestUnindexWaitsOutASlowReclaim(t *testing.T) {
	isolate(t)
	s := &rootsServer{real: body(false), delay: 200 * time.Millisecond}
	stageRoots(t, s)
	unindexFlags(t, false, true)

	old := api.DefaultTimeout
	api.DefaultTimeout = 50 * time.Millisecond
	t.Cleanup(func() { api.DefaultTimeout = old })

	if _, _, err := runUnindex(t, "/t"); err != nil {
		t.Errorf("unindex gave up on a slow reclaim: %v", err)
	}
}

func TestPruneWaitsOutASlowReclaim(t *testing.T) {
	isolate(t)
	stageRoots(t, &rootsServer{delay: 200 * time.Millisecond})

	old := api.DefaultTimeout
	api.DefaultTimeout = 50 * time.Millisecond
	t.Cleanup(func() { api.DefaultTimeout = old })

	pruneDryRun, pruneJSON = false, false
	if _, _, err := capture(t, func() error { return pruneCmd.RunE(pruneCmd, nil) }); err != nil {
		t.Errorf("prune gave up on a slow reclaim: %v", err)
	}
}
