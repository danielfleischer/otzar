package cmd

import (
	"errors"
	"fmt"
	"os"
	"time"

	"otzar/internal/api"
	"otzar/internal/buildinfo"
	"otzar/internal/lifecycle"
)

// autoStartTimeout bounds a boot triggered by an index-touching verb. It matches
// the default on `otzar server start` because it is the same cold JVM.
const autoStartTimeout = 60 * time.Second

// client returns an api.Client for the local server, booting one first if it
// isn't already running.
//
// The rule: verbs that talk to the *index* auto-start; verbs that talk to the
// *server process* don't. So index, query, list, and admin * go through here,
// while everything under `otzar server` keeps calling api.New() directly — a
// `server status` that started what it was asked to report on would be lying,
// and "not running" is the answer it exists to give.
//
// `otzar server watch status` is the awkward edge: it reads a toggle the server
// persists, so it needs one running, yet it lives under `server`. It stays
// explicit — the small inconsistency beats a status command with side effects.
func client() (*api.Client, error) {
	if err := ensureServer(); err != nil {
		return nil, err
	}
	return api.New()
}

// maintenanceClient is client() without a total deadline, for the verbs that
// reclaim deleted documents (unindex, prune). A reclaim rewrites every segment
// that carries deletes — on a real index that is a vector file in the hundreds of
// megabytes and its HNSW graph, which takes well past the default budget. A
// deadline could not bound that work anyway: the server finishes regardless, so
// the only thing it bounded was the client's patience, turning a completed unindex
// into "context deadline exceeded". The cost is proportional to the index, not
// open-ended; Ctrl-C remains the way out.
func maintenanceClient() (*api.Client, error) {
	if err := ensureServer(); err != nil {
		return nil, err
	}
	return api.NewWithTimeout(0)
}

// ensureServer boots the server unless one is already healthy.
//
// The health check up front is what makes the notice honest. Start already
// reports ErrAlreadyRunning for a server that was up, but only after it has
// looked — a notice printed on that answer would arrive after the wait it is
// meant to explain. Asking first costs one request to loopback.
//
// A racer can still boot between the two checks; Start's own lock resolves that
// and ErrAlreadyRunning is success here, exactly as it is for `otzar mcp`.
//
// The notice goes to stderr so `otzar list | wc -l` and `otzar query -j | jq`
// stay clean: a two-second pause is worth explaining to a human, but it is not
// part of the output.
func ensureServer() error {
	if h, ok := lifecycle.Probe(); ok {
		warnVersionSkew(h.Version)
		return nil
	}
	fmt.Fprintln(os.Stderr, "starting server…")
	if err := lifecycle.Start(autoStartTimeout); err != nil && !errors.Is(err, lifecycle.ErrAlreadyRunning) {
		return err
	}
	// One more probe, now that there is something to ask: a boot we lost the
	// race for (ErrAlreadyRunning) can have attached us to a server this binary
	// didn't install, which is exactly the case worth reporting.
	if h, ok := lifecycle.Probe(); ok {
		warnVersionSkew(h.Version)
	}
	return nil
}

// versionSkew is the warning owed to a user whose running server is not the one
// this binary shipped with, or "" when there is nothing worth saying.
//
// Upgrading an install replaces the jar on disk but leaves any running server
// alone — nothing restarts it, and the client attaches to whatever is already
// healthy. So the pair silently drifts until someone says so.
//
// It warns rather than refuses: the API is compatible far more often than not,
// and a hard failure would strand a user mid-task over a difference that may not
// matter. Silent when either side reports "dev", where a source checkout is
// *expected* to differ from the installed half, and when the server reports
// nothing at all — that server predates /health carrying a version, which is a
// difference the user can do nothing more about than the one we'd be reporting.
func versionSkew(server string) string {
	client := buildinfo.Version
	if server == "" || server == buildinfo.Dev || client == buildinfo.Dev || server == client {
		return ""
	}
	return fmt.Sprintf("warning: running server is %s but this client is %s; "+
		"run `otzar server restart` to load the installed server", server, client)
}

// warnVersionSkew prints versionSkew's verdict to stderr, where it stays out of
// piped output and out of the MCP host's JSON-RPC channel.
func warnVersionSkew(server string) {
	if msg := versionSkew(server); msg != "" {
		fmt.Fprintln(os.Stderr, msg)
	}
}
