package lifecycle

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// TestResolveServerDirBaked verifies the build-time-baked serverDir is used as a
// fallback: with OTZER_SERVER_DIR unset and no ./server in the working dir, the
// baked path (validated by its deps.edn) is returned.
func TestResolveServerDirBaked(t *testing.T) {
	t.Setenv("OTZER_SERVER_DIR", "")

	// Run from a scratch cwd so the ./server/deps.edn check misses.
	cwd, _ := os.Getwd()
	t.Cleanup(func() { _ = os.Chdir(cwd) })
	if err := os.Chdir(t.TempDir()); err != nil {
		t.Fatal(err)
	}

	baked := t.TempDir()
	if err := os.WriteFile(filepath.Join(baked, "deps.edn"), []byte("{}"), 0o644); err != nil {
		t.Fatal(err)
	}
	old := serverDir
	serverDir = baked
	t.Cleanup(func() { serverDir = old })

	got, err := resolveServerDir()
	if err != nil {
		t.Fatalf("resolveServerDir: %v", err)
	}
	if got != baked {
		t.Fatalf("got %q, want baked path %q", got, baked)
	}
}

// TestStartLockMutualExclusion verifies the file lock that serializes server
// startup: while one holder has it, a second acquisition blocks until timeout,
// and once released it can be taken again.
func TestStartLockMutualExclusion(t *testing.T) {
	t.Setenv("HOME", t.TempDir()) // dir() -> $HOME/.otzer, so the lock is isolated
	if err := os.MkdirAll(dir(), 0o700); err != nil {
		t.Fatal(err)
	}

	unlock, err := acquireStartLock(time.Second)
	if err != nil {
		t.Fatalf("first acquire: %v", err)
	}

	// Second acquire must wait out its whole timeout while the lock is held.
	start := time.Now()
	if _, err := acquireStartLock(300 * time.Millisecond); err == nil {
		t.Fatal("second acquire succeeded while the lock was held")
	}
	if waited := time.Since(start); waited < 250*time.Millisecond {
		t.Fatalf("second acquire returned after only %s; it should have blocked", waited)
	}

	unlock()

	// After release it should be immediately acquirable again.
	unlock2, err := acquireStartLock(time.Second)
	if err != nil {
		t.Fatalf("acquire after release: %v", err)
	}
	unlock2()
}
