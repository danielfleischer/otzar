package lifecycle

import (
	"os"
	"testing"
	"time"
)

// TestStartLockMutualExclusion verifies the file lock that serializes server
// startup: while one holder has it, a second acquisition blocks until timeout,
// and once released it can be taken again.
func TestStartLockMutualExclusion(t *testing.T) {
	t.Setenv("HOME", t.TempDir()) // dir() -> $HOME/.myrag, so the lock is isolated
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
