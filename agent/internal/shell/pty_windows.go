//go:build windows

package shell

import (
	"github.com/UserExistsError/conpty"
)

// windowsPTY runs a shell in a Windows pseudo console (ConPTY, CGO-free).
type windowsPTY struct {
	cpty *conpty.ConPty
}

// defaultShell uses PowerShell for an interactive experience on Windows.
func defaultShell() string {
	return "powershell.exe"
}

// startPTY launches shellPath in a ConPTY of the given size.
func startPTY(shellPath string, rows, cols uint16) (ptySession, error) {
	cpty, err := conpty.Start(shellPath, conpty.ConPtyDimensions(int(cols), int(rows)))
	if err != nil {
		return nil, err
	}
	return &windowsPTY{cpty: cpty}, nil
}

func (p *windowsPTY) Read(b []byte) (int, error)  { return p.cpty.Read(b) }
func (p *windowsPTY) Write(b []byte) (int, error) { return p.cpty.Write(b) }

func (p *windowsPTY) Resize(rows, cols uint16) error {
	return p.cpty.Resize(int(cols), int(rows))
}

func (p *windowsPTY) Close() error {
	return p.cpty.Close()
}
