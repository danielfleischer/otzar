package cmd

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"strings"
)

// confirm asks a yes/no question on stderr and reports whether it was approved.
// strict requires the literal word "yes"; otherwise y/yes (case-insensitive)
// approves. It requires an interactive terminal, returning an error rather than
// hanging on the read when stdin is a pipe — callers gate it behind a --yes flag
// for non-interactive use. The prompt goes to stderr so a --json stdout stays clean.
func confirm(prompt string, strict bool) (bool, error) {
	if !isTerminal(os.Stdin) {
		return false, errors.New("refusing without confirmation; pass --yes to skip the prompt")
	}
	fmt.Fprint(os.Stderr, prompt)
	line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
	ans := strings.ToLower(strings.TrimSpace(line))
	if strict {
		return ans == "yes", nil
	}
	return ans == "y" || ans == "yes", nil
}
