package cmd

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// isolate points every lookup at scratch directories: a scratch HOME (so the
// port file and ~/.otzar are this test's own) and XDG_DATA_HOME plus a scratch
// cwd and cleared overrides, so that no server installed on the developer's
// machine — a jar beside the binary, a ./server checkout — can be found and
// actually booted by a test.
func isolate(t *testing.T) {
	t.Helper()
	for _, k := range []string{"OTZAR_SERVER_DIR", "OTZAR_SERVER_JAR", "OTZAR_JAVA", "JAVA_HOME"} {
		t.Setenv(k, "")
	}
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_DATA_HOME", filepath.Join(home, "xdg"))

	cwd, _ := os.Getwd()
	t.Cleanup(func() { _ = os.Chdir(cwd) })
	if err := os.Chdir(t.TempDir()); err != nil {
		t.Fatal(err)
	}
}

// stageServer stands up a fake healthy server and publishes its port the way the
// real one does, so lifecycle.IsHealthy and api.New both find it.
func stageServer(t *testing.T) {
	t.Helper()
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" {
			w.WriteHeader(http.StatusOK)
			return
		}
		http.NotFound(w, r)
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

// TestClientUsesRunningServer is the common path: a server is already up, so
// client() must hand back a Client without trying to boot anything. Nothing is
// installed under this test's isolated HOME, so an attempted boot could only
// fail — succeeding here is what proves the health check short-circuits it.
func TestClientUsesRunningServer(t *testing.T) {
	isolate(t)
	stageServer(t)

	c, err := client()
	if err != nil {
		t.Fatalf("client(): %v", err)
	}
	if c.BaseURL == "" {
		t.Error("want a resolved BaseURL")
	}
}

// TestClientStartsWhenNotRunning covers the point of the change: with no server
// running, client() must try to start one rather than fall through to the old
// "server not running (no port file)" error. Nothing is installed here, so the
// attempt fails at resolution — that specific failure is the evidence a boot was
// attempted at all.
func TestClientStartsWhenNotRunning(t *testing.T) {
	isolate(t)

	_, err := client()
	if err == nil {
		t.Fatal("want an error when no server is running and none can be located")
	}
	if strings.Contains(err.Error(), "no port file") {
		t.Errorf("fell through to api.New() instead of attempting a start: %v", err)
	}
	if !strings.Contains(err.Error(), "cannot locate the server") {
		t.Errorf("want the server-resolution failure, got: %v", err)
	}
}
