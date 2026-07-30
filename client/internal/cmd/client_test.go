package cmd

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"otzar/internal/buildinfo"
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
// real one does, so lifecycle.Probe and api.New both find it. The empty /health
// body doubles as the old-server case: healthy, but reporting no version.
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

// TestVersionSkew pins when the client speaks up about a version difference. The
// silent cases matter more than the loud one: a developer running a checkout
// against an installed jar, or anyone on a server too old to report a version,
// must not be nagged on every command.
func TestVersionSkew(t *testing.T) {
	cases := []struct {
		name         string
		client       string
		server       string
		wantSkewWarn bool
	}{
		{"matching release", "v1.0.1", "v1.0.1", false},
		{"stale server after upgrade", "v1.0.2", "v1.0.1", true},
		{"client older than server", "v1.0.1", "v1.0.2", true},
		{"server predates version reporting", "v1.0.1", "", false},
		{"server from source", "v1.0.1", "dev", false},
		{"client from source", "dev", "v1.0.1", false},
		{"both from source", "dev", "dev", false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			prev := buildinfo.Version
			buildinfo.Version = tc.client
			t.Cleanup(func() { buildinfo.Version = prev })

			msg := versionSkew(tc.server)
			if got := msg != ""; got != tc.wantSkewWarn {
				t.Fatalf("versionSkew(%q) with client %q = %q; want warning: %v",
					tc.server, tc.client, msg, tc.wantSkewWarn)
			}
			if tc.wantSkewWarn && !strings.Contains(msg, "server restart") {
				t.Errorf("warning should say how to fix it, got: %q", msg)
			}
		})
	}
}

// TestSourceRow covers the fallback that matters: with a running server we show
// what it says about itself, and only without that fact do we fall back to the
// prediction — labelled, because it is resolved from the current directory and
// can name a checkout while an installed jar is what is actually serving.
func TestSourceRow(t *testing.T) {
	const wouldBoot = "/home/me/my-rag/server (source)"

	if got := sourceRow("/opt/otzar/otzar-server.jar", wouldBoot); got != "/opt/otzar/otzar-server.jar" {
		t.Errorf("with a live answer, want it verbatim, got %q", got)
	}

	got := sourceRow("", wouldBoot)
	if !strings.HasPrefix(got, wouldBoot) {
		t.Errorf("want the prediction as a fallback, got %q", got)
	}
	if !strings.Contains(got, "would boot") {
		t.Errorf("the fallback must not read as fact, got %q", got)
	}
}
