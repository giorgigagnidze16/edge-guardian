// Package shell provides interactive PTY sessions that stream a device shell
// to the controller over MQTT, backing the dashboard's web terminal.
package shell

import "io"

// ptySession is an OS pseudo-terminal running an interactive shell process.
// Backends are platform-specific (pty_unix.go / pty_windows.go).
type ptySession interface {
	io.ReadWriteCloser
	// Resize sets the terminal window size in character cells.
	Resize(rows, cols uint16) error
}
