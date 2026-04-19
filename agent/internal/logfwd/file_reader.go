// file_reader.go provides cross-platform file-based log tailing.

package logfwd

import (
	"bufio"
	"context"
	"os"
	"time"

	"go.uber.org/zap"
)

// FileTailer reads new lines from a log file and pushes them to a Forwarder.
type FileTailer struct {
	path      string
	forwarder *Forwarder
	logger    *zap.Logger
}

// NewFileTailer creates a tailer for the given file path.
func NewFileTailer(path string, forwarder *Forwarder, logger *zap.Logger) *FileTailer {
	return &FileTailer{
		path:      path,
		forwarder: forwarder,
		logger:    logger,
	}
}

// Run tails the file, pushing new lines to the forwarder.
// It seeks to the end on start and follows new writes.
func (t *FileTailer) Run(ctx context.Context) {
	f, err := os.Open(t.path)
	if err != nil {
		t.logger.Warn("cannot open log file for tailing", zap.String("path", t.path), zap.Error(err))
		return
	}
	defer f.Close()

	// Seek to end of file
	if _, err := f.Seek(0, 2); err != nil {
		t.logger.Warn("cannot seek to end of log file", zap.Error(err))
	}

	scanner := bufio.NewScanner(f)
	pollInterval := 500 * time.Millisecond

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		if scanner.Scan() {
			line := scanner.Text()
			if line != "" {
				t.forwarder.Push(Entry{
					Timestamp: time.Now().UTC(),
					Level:     "INFO",
					Message:   line,
					Source:    "file:" + t.path,
				})
			}
		} else {
			// No more data - wait and retry
			time.Sleep(pollInterval)
			// Re-check for new data (scanner resumes from last position)
		}
	}
}
