package lifecycle

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"
)

// isolate points every lookup resolveServer does at scratch directories: a
// scratch cwd (so the ./server/deps.edn probe misses), a scratch HOME and
// XDG_DATA_HOME (so an installed jar on the developer's machine can't leak in),
// no baked serverDir, and no environment overrides.
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

	old := serverDir
	serverDir = ""
	t.Cleanup(func() { serverDir = old })
}

// fakeJava writes an executable stub that exits with the given code, standing in
// for a JVM. `exit 0` is a working java; anything else is a broken one, like the
// /usr/bin/java shim macOS ships when no JDK is installed.
func fakeJava(t *testing.T, dir string, exitCode int) string {
	t.Helper()
	path := filepath.Join(dir, "java")
	script := fmt.Sprintf("#!/bin/sh\nexit %d\n", exitCode)
	if err := os.WriteFile(path, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	return path
}

// TestResolveServerBakedSourceDir verifies the build-time-baked serverDir is
// used when there's no override and no ./server in the working dir: a checkout
// build keeps running the source tree it was built from.
func TestResolveServerBakedSourceDir(t *testing.T) {
	isolate(t)

	baked := t.TempDir()
	if err := os.WriteFile(filepath.Join(baked, "deps.edn"), []byte("{}"), 0o644); err != nil {
		t.Fatal(err)
	}
	serverDir = baked

	got, err := resolveServer()
	if err != nil {
		t.Fatalf("resolveServer: %v", err)
	}
	if got.cmd != "clojure" || got.dir != baked {
		t.Fatalf("got %s %v in %q, want clojure -M:run in %q", got.cmd, got.args, got.dir, baked)
	}
}

// TestResolveServerInstalledJar covers the shipped install: no checkout
// anywhere, just a jar in the user's data dir, booted by a located JVM with the
// memory options that deps.edn's :run alias applies in development.
func TestResolveServerInstalledJar(t *testing.T) {
	isolate(t)

	dataDir := filepath.Join(os.Getenv("XDG_DATA_HOME"), "otzar")
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		t.Fatal(err)
	}
	jar := filepath.Join(dataDir, jarName)
	if err := os.WriteFile(jar, []byte("not really a jar"), 0o644); err != nil {
		t.Fatal(err)
	}
	java := fakeJava(t, t.TempDir(), 0)
	t.Setenv("OTZAR_JAVA", java)

	got, err := resolveServer()
	if err != nil {
		t.Fatalf("resolveServer: %v", err)
	}
	if got.cmd != java {
		t.Fatalf("cmd = %q, want the located java %q", got.cmd, java)
	}
	if len(got.args) < 2 || got.args[len(got.args)-2] != "-jar" || got.args[len(got.args)-1] != jar {
		t.Fatalf("args = %v, want them to end in -jar %s", got.args, jar)
	}
	for _, want := range jvmOpts {
		if !slices.Contains(got.args, want) {
			t.Fatalf("args = %v, missing JVM option %s", got.args, want)
		}
	}
}

// TestResolveServerJarEnvWins verifies OTZAR_SERVER_JAR overrides a source
// checkout that would otherwise be preferred, and that pointing it at a missing
// file is a clear error rather than a silent fallback.
func TestResolveServerJarEnvWins(t *testing.T) {
	isolate(t)

	baked := t.TempDir()
	if err := os.WriteFile(filepath.Join(baked, "deps.edn"), []byte("{}"), 0o644); err != nil {
		t.Fatal(err)
	}
	serverDir = baked

	jar := filepath.Join(t.TempDir(), jarName)
	if err := os.WriteFile(jar, []byte("not really a jar"), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("OTZAR_SERVER_JAR", jar)
	t.Setenv("OTZAR_JAVA", fakeJava(t, t.TempDir(), 0))

	got, err := resolveServer()
	if err != nil {
		t.Fatalf("resolveServer: %v", err)
	}
	if !slices.Contains(got.args, jar) {
		t.Fatalf("args = %v, want the jar from OTZAR_SERVER_JAR (%s)", got.args, jar)
	}

	t.Setenv("OTZAR_SERVER_JAR", filepath.Join(t.TempDir(), "absent.jar"))
	if _, err := resolveServer(); err == nil {
		t.Fatal("a missing OTZAR_SERVER_JAR should be an error, not a fallback")
	}
}

// TestResolveServerNothingInstalled verifies the failure is an explanatory error
// rather than a spawn of some arbitrary `clojure` on PATH.
func TestResolveServerNothingInstalled(t *testing.T) {
	isolate(t)

	if _, err := resolveServer(); err == nil {
		t.Fatal("want an error when neither a jar nor a checkout exists")
	}
}

// TestResolveJavaSkipsBrokenCandidate is the macOS case that makes probing
// necessary: a java that exists and is executable but fails when run must be
// passed over for one that works, not returned.
func TestResolveJavaSkipsBrokenCandidate(t *testing.T) {
	isolate(t)

	t.Setenv("OTZAR_JAVA", fakeJava(t, t.TempDir(), 1)) // first candidate, but broken

	// The next candidate is JAVA_HOME/bin/java, so stage a working one there.
	javaHome := t.TempDir()
	if err := os.MkdirAll(filepath.Join(javaHome, "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	good := fakeJava(t, filepath.Join(javaHome, "bin"), 0)
	t.Setenv("JAVA_HOME", javaHome)

	got, err := resolveJava()
	if err != nil {
		t.Fatalf("resolveJava: %v", err)
	}
	if got != good {
		t.Fatalf("resolveJava = %q, want %q (the broken candidate should be skipped)", got, good)
	}
}

// TestStartLockMutualExclusion verifies the file lock that serializes server
// startup: while one holder has it, a second acquisition blocks until timeout,
// and once released it can be taken again.
func TestStartLockMutualExclusion(t *testing.T) {
	t.Setenv("HOME", t.TempDir()) // dir() -> $HOME/.otzar, so the lock is isolated
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

func TestPidRecordRejectsReusedAndLegacyPIDs(t *testing.T) {
	oldIdentity := processIdentity
	processIdentity = func(pid int) (string, error) {
		if pid != 42 {
			t.Fatalf("processIdentity called with pid %d, want 42", pid)
		}
		return "new process start", nil
	}
	t.Cleanup(func() { processIdentity = oldIdentity })

	if ownedProcess(pidRecord{PID: 42, Started: "old process start"}) {
		t.Fatal("a recycled pid with a different start identity must not be owned")
	}
	if !ownedProcess(pidRecord{PID: 42, Started: "new process start"}) {
		t.Fatal("the recorded pid and matching start identity should be owned")
	}
	if ownedProcess(pidRecord{PID: 42}) {
		t.Fatal("a legacy bare pid has no ownership proof and must not be signalled")
	}
}

func TestReadPidRecordAcceptsLegacyPID(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	if err := os.MkdirAll(dir(), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(PidFile(), []byte("42\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	record, err := readPidRecord()
	if err != nil {
		t.Fatalf("readPidRecord: %v", err)
	}
	if record.PID != 42 || record.Started != "" {
		t.Fatalf("legacy record = %#v, want pid 42 with no start identity", record)
	}
}

func TestWritePidRecordCapturesProcessIdentity(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	if err := os.MkdirAll(dir(), 0o700); err != nil {
		t.Fatal(err)
	}
	oldIdentity := processIdentity
	processIdentity = func(pid int) (string, error) { return "captured process start", nil }
	t.Cleanup(func() { processIdentity = oldIdentity })

	if err := writePidRecord(os.Getpid()); err != nil {
		t.Fatalf("writePidRecord: %v", err)
	}
	record, err := readPidRecord()
	if err != nil {
		t.Fatalf("readPidRecord: %v", err)
	}
	if record.PID != os.Getpid() || record.Started == "" {
		t.Fatalf("record = %#v, want current pid and a start identity", record)
	}
	if !ownedProcess(record) {
		t.Fatal("a freshly recorded current process should verify as owned")
	}
}

// stageHealth publishes a port file pointing at a /health that returns body,
// the way a running server would.
func stageHealth(t *testing.T, status int, body string) {
	t.Helper()
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}))
	t.Cleanup(ts.Close)

	u, err := url.Parse(ts.URL)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(dir(), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(PortFile(), []byte(u.Port()), 0o644); err != nil {
		t.Fatal(err)
	}
}

// TestProbe covers what the probe must survive. Liveness is the status code
// alone: a server whose body we can't read or parse — an old one, a truncated
// one — is still up, and reporting it as down would send the client off to boot
// a second server on top of it.
func TestProbe(t *testing.T) {
	cases := []struct {
		name        string
		status      int
		body        string
		wantHealthy bool
		wantVersion string
	}{
		{"current server", 200, `{"status":"ok","uptime_s":1.5,"version":"v1.0.1"}`, true, "v1.0.1"},
		{"server without a version", 200, `{"status":"ok","uptime_s":1.5}`, true, ""},
		{"empty body", 200, "", true, ""},
		{"unparseable body", 200, "not json", true, ""},
		{"error status", 500, `{"version":"v1.0.1"}`, false, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("HOME", t.TempDir())
			stageHealth(t, tc.status, tc.body)

			h, ok := Probe()
			if ok != tc.wantHealthy {
				t.Fatalf("Probe() healthy = %v, want %v", ok, tc.wantHealthy)
			}
			if h.Version != tc.wantVersion {
				t.Errorf("Probe() version = %q, want %q", h.Version, tc.wantVersion)
			}
			if IsHealthy() != tc.wantHealthy {
				t.Errorf("IsHealthy() disagrees with Probe()")
			}
		})
	}
}

// TestProbeNoPortFile is the not-running case: no port file, no server, and no
// panic on the way to that answer.
func TestProbeNoPortFile(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	if _, ok := Probe(); ok {
		t.Fatal("want unhealthy with no port file")
	}
}
