// Package lifecycle owns the server process: locating it, spawning the JVM,
// tracking it via PID/port files under ~/.otzar, and tearing it down.
//
// Contract with the server (see server/src/rag/config.clj): the server picks a
// free port and writes ~/.otzar/server.port; this client writes the child PID
// and process-start identity to ~/.otzar/server.pid. The port file is the source
// of truth for "where is the server"; the pid record is how we safely signal it
// if /shutdown is unavailable.
package lifecycle

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func dir() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".otzar")
}

func PortFile() string { return filepath.Join(dir(), "server.port") }
func PidFile() string  { return filepath.Join(dir(), "server.pid") }
func LogFile() string  { return filepath.Join(dir(), "server.log") }

// ReadPort returns the port the running server published, or an error if the
// port file is missing/garbage (which we treat as "server not running").
func ReadPort() (int, error) {
	b, err := os.ReadFile(PortFile())
	if err != nil {
		return 0, err
	}
	return strconv.Atoi(strings.TrimSpace(string(b)))
}

// BaseURL is the server's HTTP root, derived from the port file.
func BaseURL() (string, error) {
	p, err := ReadPort()
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("http://127.0.0.1:%d", p), nil
}

type pidRecord struct {
	PID     int    `json:"pid"`
	Started string `json:"started"`
}

func readPidRecord() (pidRecord, error) {
	b, err := os.ReadFile(PidFile())
	if err != nil {
		return pidRecord{}, err
	}
	var record pidRecord
	if err := json.Unmarshal(b, &record); err == nil && record.PID > 0 {
		return record, nil
	}

	// Compatibility with releases that wrote only the integer PID. It is useful
	// for observing that the process still exists, but not sufficient proof that
	// a recycled PID still belongs to otzar, so ownedProcess rejects it.
	pid, err := strconv.Atoi(strings.TrimSpace(string(b)))
	if err != nil {
		return pidRecord{}, err
	}
	return pidRecord{PID: pid}, nil
}

// Health is what a /health probe learned about the running server. Version is
// best-effort: a server too old to report one, or a body we can't parse, leaves
// it empty rather than making the server look unhealthy.
type Health struct {
	Version string `json:"version"`
}

// Probe asks /health on the published port. The bool is the liveness answer —
// the same one IsHealthy gives — and the Health carries what the response said.
func Probe() (Health, bool) {
	base, err := BaseURL()
	if err != nil {
		return Health{}, false
	}
	c := http.Client{Timeout: 500 * time.Millisecond}
	resp, err := c.Get(base + "/health")
	if err != nil {
		return Health{}, false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return Health{}, false
	}
	var h Health
	// A short read cap: /health is tiny, and a wedged server that streams
	// forever must not hang a liveness check.
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err == nil {
		_ = json.Unmarshal(body, &h)
	}
	return h, true
}

// IsHealthy reports whether the server answers /health on its published port.
func IsHealthy() bool {
	_, ok := Probe()
	return ok
}

func processAlive(pid int) bool {
	if pid <= 0 {
		return false
	}
	// On Unix, signal 0 probes for existence without affecting the process.
	return syscall.Kill(pid, 0) == nil
}

// processIdentity returns the OS-recorded start time for pid. PID values can be
// recycled after a crash; the pair (pid, start identity) lets Stop distinguish
// the child Start actually spawned from an unrelated process that later received
// the same integer PID. `ps -o lstart` is available on the supported Unix hosts.
var processIdentity = func(pid int) (string, error) {
	if pid <= 0 {
		return "", errors.New("invalid pid")
	}
	out, err := exec.Command("ps", "-p", strconv.Itoa(pid), "-o", "lstart=").Output()
	if err != nil {
		return "", err
	}
	identity := strings.TrimSpace(string(out))
	if identity == "" {
		return "", errors.New("process has no start identity")
	}
	return identity, nil
}

func ownedProcess(record pidRecord) bool {
	if record.PID <= 0 || record.Started == "" {
		return false
	}
	current, err := processIdentity(record.PID)
	return err == nil && current == record.Started
}

func writePidRecord(pid int) error {
	started, err := processIdentity(pid)
	if err != nil {
		return fmt.Errorf("identifying server process %d: %w", pid, err)
	}
	b, err := json.Marshal(pidRecord{PID: pid, Started: started})
	if err != nil {
		return err
	}
	return os.WriteFile(PidFile(), b, 0o600)
}

// serverDir is the absolute path to the Clojure server dir, baked in at build
// time via -ldflags (-X otzar/internal/lifecycle.serverDir=...); see Makefile.
// Empty when built without that flag (plain `go build`, `go test`).
var serverDir string

// launch is how to boot the server. There are two shapes: the packaged jar run
// by a JVM we locate ourselves, which is how a release is installed, and the
// source tree run by the Clojure CLI, which is how development works.
type launch struct {
	cmd  string   // executable to run
	args []string // its arguments
	dir  string   // working directory, empty to inherit
	desc string   // what we found, for `server status` and error messages
}

// jvmOpts mirrors the :run alias in server/deps.edn: a single-user local daemon
// on SerialGC with a 512m ceiling that exits rather than thrashes on OOM. A jar
// can't carry these, so the launcher supplies them — keep the two in sync.
//
// Deliberately free of version-gated flags. Lucene would like
// --enable-native-access=ALL-UNNAMED and --add-modules jdk.incubator.vector (it
// warns about both on boot), but an unrecognized option makes a JVM refuse to
// start outright, and we don't control which JDK 21+ the user has. The warnings
// are cosmetic and land in server.log.
var jvmOpts = []string{"-Xms64m", "-Xmx512m", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError"}

// jarLaunch boots a packaged server jar, locating a JVM to run it with.
func jarLaunch(jar string) (*launch, error) {
	java, err := resolveJava()
	if err != nil {
		return nil, err
	}
	return &launch{
		cmd:  java,
		args: append(append([]string{}, jvmOpts...), "-jar", jar),
		desc: jar,
	}, nil
}

// srcLaunch boots the server from its source tree via the Clojure CLI, which
// brings its own JVM and applies the :run alias's options itself.
func srcLaunch(dir string) *launch {
	return &launch{cmd: "clojure", args: []string{"-M:run"}, dir: dir, desc: dir + " (source)"}
}

// jarCandidates lists where an installed server jar may live, in order. These
// are all overridable by OTZAR_SERVER_JAR; they exist so that the common
// installs — both files in one directory, or a --prefix-style tree — work with
// no configuration at all.
func jarCandidates() []string {
	var c []string
	if exe, err := os.Executable(); err == nil {
		if exe, err := filepath.EvalSymlinks(exe); err == nil {
			bin := filepath.Dir(exe)
			c = append(c,
				filepath.Join(bin, jarName),                         // alongside the binary
				filepath.Join(bin, "..", "share", "otzar", jarName), // <prefix>/bin + <prefix>/share
				filepath.Join(bin, "..", "lib", "otzar", jarName),
			)
		}
	}
	if xdg := os.Getenv("XDG_DATA_HOME"); xdg != "" {
		c = append(c, filepath.Join(xdg, "otzar", jarName))
	}
	if home, err := os.UserHomeDir(); err == nil {
		c = append(c, filepath.Join(home, ".local", "share", "otzar", jarName))
	}
	return c
}

const jarName = "otzar-server.jar"

// resolveServer decides how to start the server. Explicit environment overrides
// win; then a development source tree, so that working in a checkout keeps using
// the code you're editing even if a release is also installed; then an installed
// jar.
func resolveServer() (*launch, error) {
	if j := os.Getenv("OTZAR_SERVER_JAR"); j != "" {
		if !fileExists(j) {
			return nil, fmt.Errorf("OTZAR_SERVER_JAR is set to %s, which does not exist", j)
		}
		return jarLaunch(j)
	}
	if d := os.Getenv("OTZAR_SERVER_DIR"); d != "" {
		return srcLaunch(d), nil
	}
	if fileExists(filepath.Join("server", "deps.edn")) {
		if abs, err := filepath.Abs("server"); err == nil {
			return srcLaunch(abs), nil
		}
	}
	if serverDir != "" && fileExists(filepath.Join(serverDir, "deps.edn")) {
		return srcLaunch(serverDir), nil
	}
	for _, jar := range jarCandidates() {
		if fileExists(jar) {
			return jarLaunch(filepath.Clean(jar))
		}
	}
	if exe, err := os.Executable(); err == nil {
		cand := filepath.Join(filepath.Dir(exe), "..", "server")
		if fileExists(filepath.Join(cand, "deps.edn")) {
			if abs, err := filepath.Abs(cand); err == nil {
				return srcLaunch(abs), nil
			}
		}
	}
	return nil, fmt.Errorf("cannot locate the server: no %s beside the otzar binary, "+
		"in ~/.local/share/otzar, or at OTZAR_SERVER_JAR, and no source checkout at "+
		"OTZAR_SERVER_DIR. Download %s from a release and put it in one of those places",
		jarName, jarName)
}

// resolveJava finds a JVM to run the server jar with.
//
// It probes candidates by running them rather than trusting that the path
// exists, because macOS ships /usr/bin/java as a stub that is present and
// executable but fails at runtime when no JDK is installed — and Homebrew's
// openjdk is keg-only, so on a Mac whose only JDK came with `clojure` there is
// no working `java` on PATH at all.
func resolveJava() (string, error) {
	var cands []string
	if j := os.Getenv("OTZAR_JAVA"); j != "" {
		cands = append(cands, j)
	}
	if h := os.Getenv("JAVA_HOME"); h != "" {
		cands = append(cands, filepath.Join(h, "bin", "java"))
	}
	if runtime.GOOS == "darwin" {
		if out, err := exec.Command("/usr/libexec/java_home").Output(); err == nil {
			cands = append(cands, filepath.Join(strings.TrimSpace(string(out)), "bin", "java"))
		}
		cands = append(cands,
			"/opt/homebrew/opt/openjdk/bin/java", // Apple silicon Homebrew
			"/usr/local/opt/openjdk/bin/java",    // Intel Homebrew
		)
	}
	if p, err := exec.LookPath("java"); err == nil {
		cands = append(cands, p)
	}
	for _, c := range cands {
		if exec.Command(c, "-version").Run() == nil {
			return c, nil
		}
	}
	return "", errors.New("no working Java runtime found: install a JDK 21 or newer " +
		"(macOS: brew install openjdk), or point OTZAR_JAVA at one")
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

// ServerSource describes the server this client would boot — an installed jar or
// a source checkout — so `server status` can show it. Install problems are then
// diagnosable without reading a log or guessing which of the two is in play.
func ServerSource() string {
	spec, err := resolveServer()
	if err != nil {
		return "not found"
	}
	return spec.desc
}

// ErrAlreadyRunning is returned by Start when a healthy server already exists.
// Callers that only need to *ensure* a server is up (e.g. `otzar mcp`) should
// treat it as success rather than a failure.
var ErrAlreadyRunning = errors.New("server already running")

// acquireStartLock takes an exclusive advisory lock on ~/.otzar/start.lock,
// retrying until it is acquired or `timeout` elapses. The returned func releases
// it. It serializes server startup across processes.
func acquireStartLock(timeout time.Duration) (func(), error) {
	path := filepath.Join(dir(), "start.lock")
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	deadline := time.Now().Add(timeout)
	for {
		switch err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err {
		case nil:
			return func() {
				_ = syscall.Flock(int(f.Fd()), syscall.LOCK_UN)
				_ = f.Close()
			}, nil
		case syscall.EWOULDBLOCK:
			if time.Now().After(deadline) {
				_ = f.Close()
				return nil, errors.New("timed out waiting for another process to finish starting the server")
			}
			time.Sleep(100 * time.Millisecond)
		default:
			_ = f.Close()
			return nil, fmt.Errorf("locking %s: %w", path, err)
		}
	}
}

// Start spawns the server (if not already healthy) and blocks until it answers
// /health or the timeout elapses. It is safe to call concurrently from multiple
// processes: startup is serialized on a file lock so exactly one server is
// spawned, and the losers observe that server and return ErrAlreadyRunning.
func Start(timeout time.Duration) error {
	if IsHealthy() {
		return ErrAlreadyRunning
	}
	spec, err := resolveServer()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir(), 0o700); err != nil {
		return err
	}
	// Lock ~/.otzar to the owner, tightening it even if it pre-existed with
	// looser bits (MkdirAll won't chmod an existing dir).
	_ = os.Chmod(dir(), 0o700)

	// Serialize startup across processes. Without this, several cold `otzar mcp`
	// launches — an LLM host opening multiple sessions, or the MCP Inspector
	// reconnecting — each see "not healthy" during the ~1-2s JVM boot and spawn
	// their own server; the duplicates then collide on Lucene's single-writer
	// index lock. Holding this lock across the whole boot makes them queue.
	unlock, err := acquireStartLock(timeout)
	if err != nil {
		return err
	}
	defer unlock()

	// A racer may have won the lock and finished booting while we waited on it.
	if IsHealthy() {
		return ErrAlreadyRunning
	}

	// Drop any stale port file so we don't race against an old value.
	_ = os.Remove(PortFile())

	logf, err := os.OpenFile(LogFile(), os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	defer logf.Close()

	cmd := exec.Command(spec.cmd, spec.args...)
	cmd.Dir = spec.dir
	cmd.Stdout = logf
	cmd.Stderr = logf
	// New process group so the server survives this short-lived CLI exiting.
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	if err := cmd.Start(); err != nil {
		return fmt.Errorf("spawning server: %w", err)
	}
	if err := writePidRecord(cmd.Process.Pid); err != nil {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		return err
	}

	// Reap the child in the background. If it exits before becoming healthy,
	// that's a crash; we learn about it via this channel. We can't poll the PID
	// instead because `clojure` execs into `java`, and that transition races
	// with a naive liveness check. Once the server is healthy we return and let
	// this CLI exit — the server keeps running in its own process group.
	exited := make(chan error, 1)
	go func() { exited <- cmd.Wait() }()

	deadline := time.Now().Add(timeout)
	for {
		if IsHealthy() {
			return nil
		}
		select {
		case <-exited:
			return fmt.Errorf("server exited during startup (%s); see %s", spec.desc, LogFile())
		case <-time.After(200 * time.Millisecond):
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("server did not become healthy within %s; see %s", timeout, LogFile())
		}
	}
}

// Stop asks the server to exit via POST /shutdown, falling back to SIGTERM, and
// cleans up the PID/port files.
func Stop(timeout time.Duration) error {
	base, err := BaseURL()
	running := err == nil && IsHealthy()
	record, pidErr := readPidRecord()

	if !running && pidErr != nil {
		return errors.New("server is not running")
	}
	if !running && !processAlive(record.PID) {
		cleanup()
		return nil
	}
	if !running && !ownedProcess(record) {
		return fmt.Errorf("refusing to signal pid %d because it cannot be verified as the "+
			"otzar server; remove the stale file %s if that process is unrelated",
			record.PID, PidFile())
	}

	if running {
		c := http.Client{Timeout: 2 * time.Second}
		if resp, err := c.Post(base+"/shutdown", "application/json", nil); err == nil {
			resp.Body.Close()
		}
	}

	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if !IsHealthy() {
			cleanup()
			return nil
		}
		time.Sleep(150 * time.Millisecond)
	}

	// Graceful path timed out — escalate.
	if pidErr != nil {
		return fmt.Errorf("server did not stop gracefully and its pid file is unavailable: %w", pidErr)
	}
	if !processAlive(record.PID) {
		cleanup()
		return nil
	}
	if !ownedProcess(record) {
		return fmt.Errorf("refusing to signal pid %d because it no longer matches the "+
			"otzar server process that was started", record.PID)
	}
	if err := syscall.Kill(record.PID, syscall.SIGTERM); err != nil {
		return fmt.Errorf("stopping server pid %d: %w", record.PID, err)
	}
	time.Sleep(500 * time.Millisecond)
	cleanup()
	return nil
}

func cleanup() {
	_ = os.Remove(PortFile())
	_ = os.Remove(PidFile())
}
