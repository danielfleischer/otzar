// Package buildinfo holds values stamped into release binaries at link time.
package buildinfo

// Version is shared by every protocol surface the binary exposes. A plain
// `go build` or `go test` reports "dev"; release builds replace it with the
// repository version through the Makefile's -ldflags.
var Version = "dev"
