// Package buildinfo holds values stamped into release binaries at link time.
package buildinfo

// Dev is the version an unstamped build reports. The server uses the same word
// when it runs from source, and neither side compares versions against it: a
// checkout is expected to differ from whatever is installed.
const Dev = "dev"

// Version is shared by every protocol surface the binary exposes. A plain
// `go build` or `go test` reports "dev"; release builds replace it with the
// repository version through the Makefile's -ldflags.
var Version = Dev
