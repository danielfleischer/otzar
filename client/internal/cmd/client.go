package cmd

import (
	"errors"
	"fmt"
	"os"
	"time"

	"otzar/internal/api"
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
	if lifecycle.IsHealthy() {
		return nil
	}
	fmt.Fprintln(os.Stderr, "starting server…")
	if err := lifecycle.Start(autoStartTimeout); err != nil && !errors.Is(err, lifecycle.ErrAlreadyRunning) {
		return err
	}
	return nil
}
