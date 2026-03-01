//go:build !windows

package main

import (
	"os"
	"syscall"
)

func platformSignals() []os.Signal {
	return []os.Signal{syscall.SIGINT, syscall.SIGTERM}
}
