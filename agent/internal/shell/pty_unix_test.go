//go:build !windows

package shell

import (
	"io"
	"strings"
	"testing"
	"time"
)

// readUntilIdle drains r until it errors (the PTY master returns EIO once the
// child exits and closes the slave) or no new bytes arrive for the timeout.
func readUntilIdle(t *testing.T, r io.Reader, timeout time.Duration) string {
	t.Helper()
	var sb strings.Builder
	done := make(chan struct{})
	go func() {
		buf := make([]byte, 1024)
		for {
			n, err := r.Read(buf)
			if n > 0 {
				sb.Write(buf[:n])
			}
			if err != nil {
				break
			}
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(timeout):
		t.Fatalf("timed out reading PTY output after %v", timeout)
	}
	return sb.String()
}

func TestStartPTY_RunsShellAndStreamsOutput(t *testing.T) {
	pty, err := startPTY("/bin/sh", 24, 80)
	if err != nil {
		t.Fatalf("startPTY: %v", err)
	}
	defer pty.Close()

	if _, err := io.WriteString(pty, "echo edgeguardian-pty-ok\nexit\n"); err != nil {
		t.Fatalf("write to pty: %v", err)
	}

	out := readUntilIdle(t, pty, 5*time.Second)
	if !strings.Contains(out, "edgeguardian-pty-ok") {
		t.Fatalf("expected PTY output to contain marker, got: %q", out)
	}
}

func TestStartPTY_ResizeSucceeds(t *testing.T) {
	pty, err := startPTY("/bin/sh", 24, 80)
	if err != nil {
		t.Fatalf("startPTY: %v", err)
	}
	defer pty.Close()

	if err := pty.Resize(40, 120); err != nil {
		t.Fatalf("resize: %v", err)
	}
}
