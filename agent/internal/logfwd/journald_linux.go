//go:build linux && !android

package logfwd

import (
	"bufio"
	"context"
	"os/exec"
	"time"

	"go.uber.org/zap"
)

// JournaldReader reads log entries from systemd journal and pushes them
// to a Forwarder. Uses `journalctl --follow` subprocess.
type JournaldReader struct {
	unit      string
	forwarder *Forwarder
	logger    *zap.Logger
}

// NewJournaldReader creates a reader for the given systemd unit.
// If unit is empty, it reads all journal entries.
func NewJournaldReader(unit string, forwarder *Forwarder, logger *zap.Logger) *JournaldReader {
	return &JournaldReader{
		unit:      unit,
		forwarder: forwarder,
		logger:    logger,
	}
}

// Run starts following the journal. Blocks until context is cancelled.
func (r *JournaldReader) Run(ctx context.Context) {
	args := []string{"--follow", "--no-pager", "--output=short-iso"}
	if r.unit != "" {
		args = append(args, "--unit="+r.unit)
	}

	cmd := exec.CommandContext(ctx, "journalctl", args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		r.logger.Error("failed to create journalctl stdout pipe", zap.Error(err))
		return
	}

	if err := cmd.Start(); err != nil {
		r.logger.Warn("failed to start journalctl", zap.Error(err))
		return
	}

	r.logger.Info("journald reader started", zap.String("unit", r.unit))

	scanner := bufio.NewScanner(stdout)
	for scanner.Scan() {
		line := scanner.Text()
		if line != "" {
			r.forwarder.Push(Entry{
				Timestamp: time.Now().UTC(),
				Level:     "INFO",
				Message:   line,
				Source:    "journald",
			})
		}
	}

	if err := cmd.Wait(); err != nil && ctx.Err() == nil {
		r.logger.Warn("journalctl exited", zap.Error(err))
	}
}
