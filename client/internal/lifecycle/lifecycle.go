// Package lifecycle owns the server process: locating it, spawning the JVM,
// tracking it via PID/port files under ~/.myrag, and tearing it down.
//
// Contract with the server (see server/src/rag/config.clj): the server picks a
// free port and writes ~/.myrag/server.port; this client writes the child PID
// to ~/.myrag/server.pid. The port file is the source of truth for "where is
// the server"; the pid file is how we signal it if /shutdown is unavailable.
package lifecycle

import (
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func dir() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".myrag")
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

func readPid() (int, error) {
	b, err := os.ReadFile(PidFile())
	if err != nil {
		return 0, err
	}
	return strconv.Atoi(strings.TrimSpace(string(b)))
}

// IsHealthy reports whether the server answers /health on its published port.
func IsHealthy() bool {
	base, err := BaseURL()
	if err != nil {
		return false
	}
	c := http.Client{Timeout: 500 * time.Millisecond}
	resp, err := c.Get(base + "/health")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == http.StatusOK
}

func processAlive(pid int) bool {
	if pid <= 0 {
		return false
	}
	// On Unix, signal 0 probes for existence without affecting the process.
	return syscall.Kill(pid, 0) == nil
}

// resolveServerDir finds the Clojure server directory. During development the
// server lives at <repo>/server; once we ship an uberjar this goes away.
func resolveServerDir() (string, error) {
	if d := os.Getenv("MYRAG_SERVER_DIR"); d != "" {
		return d, nil
	}
	if _, err := os.Stat(filepath.Join("server", "deps.edn")); err == nil {
		return filepath.Abs("server")
	}
	if exe, err := os.Executable(); err == nil {
		cand := filepath.Join(filepath.Dir(exe), "..", "server")
		if _, err := os.Stat(filepath.Join(cand, "deps.edn")); err == nil {
			return filepath.Abs(cand)
		}
	}
	return "", errors.New("cannot locate the Clojure server; set MYRAG_SERVER_DIR to <repo>/server")
}

// Start spawns the server (if not already healthy) and blocks until it answers
// /health or the timeout elapses.
func Start(timeout time.Duration) error {
	if IsHealthy() {
		return errors.New("server already running")
	}
	srvDir, err := resolveServerDir()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir(), 0o755); err != nil {
		return err
	}
	// Drop any stale port file so we don't race against an old value.
	_ = os.Remove(PortFile())

	logf, err := os.OpenFile(LogFile(), os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	defer logf.Close()

	cmd := exec.Command("clojure", "-M:run")
	cmd.Dir = srvDir
	cmd.Stdout = logf
	cmd.Stderr = logf
	// New process group so the server survives this short-lived CLI exiting.
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	if err := cmd.Start(); err != nil {
		return fmt.Errorf("spawning server: %w", err)
	}
	if err := os.WriteFile(PidFile(), []byte(strconv.Itoa(cmd.Process.Pid)), 0o644); err != nil {
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
			return fmt.Errorf("server exited during startup; see %s", LogFile())
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
	pid, pidErr := readPid()

	if !running && pidErr != nil {
		return errors.New("server is not running")
	}

	if running {
		c := http.Client{Timeout: 2 * time.Second}
		if resp, err := c.Post(base+"/shutdown", "application/json", nil); err == nil {
			resp.Body.Close()
		}
	}

	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if !IsHealthy() && (pidErr != nil || !processAlive(pid)) {
			cleanup()
			return nil
		}
		time.Sleep(150 * time.Millisecond)
	}

	// Graceful path timed out — escalate.
	if pidErr == nil && processAlive(pid) {
		_ = syscall.Kill(pid, syscall.SIGTERM)
		time.Sleep(500 * time.Millisecond)
	}
	cleanup()
	return nil
}

func cleanup() {
	_ = os.Remove(PortFile())
	_ = os.Remove(PidFile())
}
