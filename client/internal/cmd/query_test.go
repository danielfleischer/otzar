package cmd

import (
	"testing"

	"otzer/internal/api"
)

// A hit is only worth showing if the user can go look at it, and the form that
// takes differs by source: a line reference an editor can jump to, a page number
// for a PDF. Getting the two crossed would print "paper.pdf:4" — a jump to line 4
// of a binary file.
func TestLocation(t *testing.T) {
	cases := []struct {
		name string
		hit  api.Hit
		want string
	}{
		{"line range", api.Hit{Path: "/n/a.md", Locator: "line", LocStart: 42, LocEnd: 47}, "/n/a.md:42-47"},
		{"single line", api.Hit{Path: "/n/a.md", Locator: "line", LocStart: 42, LocEnd: 42}, "/n/a.md:42"},
		{"page range", api.Hit{Path: "/n/p.pdf", Locator: "page", LocStart: 4, LocEnd: 5}, "/n/p.pdf p.4-5"},
		{"single page", api.Hit{Path: "/n/p.pdf", Locator: "page", LocStart: 4, LocEnd: 4}, "/n/p.pdf p.4"},
		// Chunks indexed before locator tracking carry no span; the path alone is
		// honest, an invented ":0" would not be.
		{"no span", api.Hit{Path: "/n/old.md"}, "/n/old.md"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := location(c.hit); got != c.want {
				t.Errorf("location() = %q, want %q", got, c.want)
			}
		})
	}
}
