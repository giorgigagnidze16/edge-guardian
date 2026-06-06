//go:build !windows

package shell

import (
	"os"
	"os/exec"

	"github.com/creack/pty"
)

// unixPTY runs a shell in a Unix pseudo-terminal via creack/pty (CGO-free).
type unixPTY struct {
	master *os.File
	cmd    *exec.Cmd
}

// defaultShell picks the device's interactive shell, preferring $SHELL.
func defaultShell() string {
	if s := os.Getenv("SHELL"); s != "" {
		return s
	}
	if _, err := os.Stat("/bin/bash"); err == nil {
		return "/bin/bash"
	}
	return "/bin/sh"
}

// startPTY launches shellPath in a pseudo-terminal of the given size.
func startPTY(shellPath string, rows, cols uint16) (ptySession, error) {
	cmd := exec.Command(shellPath)
	master, err := pty.StartWithSize(cmd, &pty.Winsize{Rows: rows, Cols: cols})
	if err != nil {
		return nil, err
	}
	return &unixPTY{master: master, cmd: cmd}, nil
}

func (p *unixPTY) Read(b []byte) (int, error)  { return p.master.Read(b) }
func (p *unixPTY) Write(b []byte) (int, error) { return p.master.Write(b) }

func (p *unixPTY) Resize(rows, cols uint16) error {
	return pty.Setsize(p.master, &pty.Winsize{Rows: rows, Cols: cols})
}

// Close stops the shell process and releases the pseudo-terminal.
func (p *unixPTY) Close() error {
	err := p.master.Close()
	if p.cmd.Process != nil {
		_ = p.cmd.Process.Kill()
		_ = p.cmd.Wait()
	}
	return err
}
