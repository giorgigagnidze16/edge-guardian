//go:build android

package logfwd

import (
	"bufio"
	"context"
	"os/exec"
	"strings"
	"time"

	"go.uber.org/zap"
)

// LogcatReader reads log entries from Android logcat and pushes them
// to a Forwarder. Mirrors the JournaldReader pattern.
type LogcatReader struct {
	tag       string // filter by tag (like unit in journald)
	forwarder *Forwarder
	logger    *zap.Logger
}

// NewLogcatReader creates a reader that streams logcat output.
// If tag is empty, it reads all logcat entries.
func NewLogcatReader(tag string, forwarder *Forwarder, logger *zap.Logger) *LogcatReader {
	return &LogcatReader{
		tag:       tag,
		forwarder: forwarder,
		logger:    logger,
	}
}

// Run starts following logcat output. Blocks until context is cancelled.
func (r *LogcatReader) Run(ctx context.Context) {
	args := []string{"-v", "threadtime"}
	if r.tag != "" {
		args = append(args, "-s", r.tag+":V")
	}

	cmd := exec.CommandContext(ctx, "logcat", args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		r.logger.Error("failed to create logcat stdout pipe", zap.Error(err))
		return
	}

	if err := cmd.Start(); err != nil {
		r.logger.Warn("failed to start logcat", zap.Error(err))
		return
	}

	r.logger.Info("logcat reader started", zap.String("tag", r.tag))

	scanner := bufio.NewScanner(stdout)
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			continue
		}

		level, message := parseLogcatLine(line)
		r.forwarder.Push(Entry{
			Timestamp: time.Now().UTC(),
			Level:     level,
			Message:   message,
			Source:    "logcat",
		})
	}

	if err := cmd.Wait(); err != nil && ctx.Err() == nil {
		r.logger.Warn("logcat exited", zap.Error(err))
	}
}

// parseLogcatLine extracts the log level and message from a logcat threadtime line.
// Format: "MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG: message"
func parseLogcatLine(line string) (level, message string) {
	// Threadtime format has the level character at a fixed position after
	// the date, time, PID, and TID fields.
	fields := strings.Fields(line)
	if len(fields) < 6 {
		return "INFO", line
	}

	// fields[0]=date, [1]=time, [2]=PID, [3]=TID, [4]=level, [5:]=tag+message
	level = mapLogcatLevel(fields[4])
	message = strings.Join(fields[5:], " ")
	return level, message
}

// mapLogcatLevel converts Android single-char log levels to standard levels.
func mapLogcatLevel(l string) string {
	switch l {
	case "V", "D":
		return "DEBUG"
	case "I":
		return "INFO"
	case "W":
		return "WARN"
	case "E":
		return "ERROR"
	case "F", "A":
		return "FATAL"
	default:
		return "INFO"
	}
}
