package shell

import (
	"errors"
	"io"
	"sync"
	"time"
)

// ptyReadChunk bounds how much PTY output is carried in a single MQTT message.
const ptyReadChunk = 16 * 1024

// Session pumps bytes between a PTY and the controller: PTY output is delivered
// to onOutput, input arrives via Write, and the shell exiting (or Close) fires
// onExit exactly once. Optional idle/max-duration limits guarantee the shell is
// reaped even if the controller disappears.
type Session struct {
	pty      ptySession
	onOutput func([]byte)
	onExit   func(error)

	idleTimeout time.Duration
	maxDuration time.Duration

	mu           sync.Mutex
	lastActivity time.Time

	done      chan struct{}
	closeOnce sync.Once
	exitOnce  sync.Once
}

func newSession(pty ptySession, onOutput func([]byte), onExit func(error)) *Session {
	return &Session{pty: pty, onOutput: onOutput, onExit: onExit, done: make(chan struct{})}
}

// withLimits sets idle and max-duration timeouts (zero disables either). Call
// before start.
func (s *Session) withLimits(idle, max time.Duration) *Session {
	s.idleTimeout = idle
	s.maxDuration = max
	return s
}

// start launches the output pump (and the timeout watchdog, if limits are set).
// It must be called exactly once.
func (s *Session) start() {
	s.touch()
	go s.readPump()
	if s.idleTimeout > 0 || s.maxDuration > 0 {
		go s.watchdog()
	}
}

func (s *Session) readPump() {
	buf := make([]byte, ptyReadChunk)
	for {
		n, err := s.pty.Read(buf)
		if n > 0 && s.onOutput != nil {
			chunk := make([]byte, n)
			copy(chunk, buf[:n])
			s.onOutput(chunk)
		}
		if err != nil {
			s.reportExit(err)
			return
		}
	}
}

// Write forwards input (keystrokes) to the shell and refreshes the idle timer.
func (s *Session) Write(p []byte) (int, error) {
	s.touch()
	return s.pty.Write(p)
}

func (s *Session) touch() {
	s.mu.Lock()
	s.lastActivity = time.Now()
	s.mu.Unlock()
}

func (s *Session) idleFor() time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	return time.Since(s.lastActivity)
}

// watchdog closes the session once the idle or max-duration limit is exceeded.
func (s *Session) watchdog() {
	start := time.Now()
	tick := watchdogTick(s.idleTimeout, s.maxDuration)
	ticker := time.NewTicker(tick)
	defer ticker.Stop()

	for {
		select {
		case <-s.done:
			return
		case <-ticker.C:
			if s.maxDuration > 0 && time.Since(start) >= s.maxDuration {
				_ = s.Close()
				return
			}
			if s.idleTimeout > 0 && s.idleFor() >= s.idleTimeout {
				_ = s.Close()
				return
			}
		}
	}
}

// watchdogTick picks a poll interval fine enough to honor the smallest limit,
// clamped to a sane range so long production timeouts don't busy-poll.
func watchdogTick(idle, max time.Duration) time.Duration {
	smallest := idle
	if max > 0 && (smallest == 0 || max < smallest) {
		smallest = max
	}
	tick := smallest / 4
	if tick < 10*time.Millisecond {
		tick = 10 * time.Millisecond
	}
	if tick > time.Second {
		tick = time.Second
	}
	return tick
}

// Resize sets the terminal window size.
func (s *Session) Resize(rows, cols uint16) error {
	return s.pty.Resize(rows, cols)
}

// Close terminates the shell and releases the PTY. Safe to call more than once.
func (s *Session) Close() error {
	var err error
	s.closeOnce.Do(func() {
		close(s.done)
		err = s.pty.Close()
	})
	return err
}

func (s *Session) reportExit(err error) {
	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrClosedPipe) {
		err = nil
	}
	s.exitOnce.Do(func() {
		if s.onExit != nil {
			s.onExit(err)
		}
	})
}
