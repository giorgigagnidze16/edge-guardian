// Package logfwd collects logs from the host system and forwards them
// via MQTT to the controller for ingestion into Loki.
package logfwd

import (
	"encoding/json"
	"sync"
	"time"

	"go.uber.org/zap"
)

// Entry represents a single log line to forward.
type Entry struct {
	Timestamp time.Time `json:"timestamp"`
	Level     string    `json:"level"`
	Message   string    `json:"message"`
	Source    string    `json:"source"`
}

// Publisher sends a batch of log entries as JSON bytes.
type Publisher func(payload []byte) error

// Forwarder collects log entries in a ring buffer and periodically
// flushes them via the configured publisher (typically MQTT).
type Forwarder struct {
	mu        sync.Mutex
	buffer    []Entry
	maxSize   int
	publisher Publisher
	logger    *zap.Logger
	stopCh    chan struct{}
}

// NewForwarder creates a log forwarder with the given ring buffer capacity.
func NewForwarder(maxSize int, publisher Publisher, logger *zap.Logger) *Forwarder {
	return &Forwarder{
		buffer:    make([]Entry, 0, maxSize),
		maxSize:   maxSize,
		publisher: publisher,
		logger:    logger,
		stopCh:    make(chan struct{}),
	}
}

// Push adds a log entry to the ring buffer. If the buffer is full,
// the oldest entry is dropped.
func (f *Forwarder) Push(entry Entry) {
	f.mu.Lock()
	defer f.mu.Unlock()

	if len(f.buffer) >= f.maxSize {
		// Drop oldest entry
		f.buffer = f.buffer[1:]
	}
	f.buffer = append(f.buffer, entry)
}

// Run starts the periodic flush loop. Call Stop() to terminate.
func (f *Forwarder) Run(flushInterval time.Duration) {
	ticker := time.NewTicker(flushInterval)
	defer ticker.Stop()

	for {
		select {
		case <-f.stopCh:
			f.flush() // Final flush
			return
		case <-ticker.C:
			f.flush()
		}
	}
}

// Stop signals the forwarder to perform a final flush and exit.
func (f *Forwarder) Stop() {
	close(f.stopCh)
}

func (f *Forwarder) flush() {
	f.mu.Lock()
	if len(f.buffer) == 0 {
		f.mu.Unlock()
		return
	}

	batch := make([]Entry, len(f.buffer))
	copy(batch, f.buffer)
	f.buffer = f.buffer[:0]
	f.mu.Unlock()

	payload, err := json.Marshal(batch)
	if err != nil {
		f.logger.Error("failed to marshal log batch", zap.Error(err))
		return
	}

	if err := f.publisher(payload); err != nil {
		f.logger.Warn("failed to publish log batch", zap.Error(err), zap.Int("entries", len(batch)))
		// Re-add failed entries to the front of the buffer (best-effort)
		f.mu.Lock()
		remaining := f.maxSize - len(f.buffer)
		if remaining > 0 {
			if len(batch) > remaining {
				batch = batch[len(batch)-remaining:]
			}
			f.buffer = append(batch, f.buffer...)
		}
		f.mu.Unlock()
	} else {
		f.logger.Debug("flushed log entries", zap.Int("count", len(batch)))
	}
}
