package shell

import (
	"sync"
	"time"

	"go.uber.org/zap"
)

// Event is a session lifecycle notification sent to the controller.
type Event struct {
	SessionID string `json:"sessionId"`
	Type      string `json:"type"` // started | exited | error
	ExitCode  int    `json:"exitCode,omitempty"`
	Msg       string `json:"msg,omitempty"`
}

// OpenRequest asks the agent to start a shell session.
type OpenRequest struct {
	SessionID string `json:"sessionId"`
	Rows      uint16 `json:"rows"`
	Cols      uint16 `json:"cols"`
	Shell     string `json:"shell,omitempty"`
}

// Control is an out-of-band action on a live session.
type Control struct {
	Type   string `json:"type"` // resize | close | signal
	Rows   uint16 `json:"rows,omitempty"`
	Cols   uint16 `json:"cols,omitempty"`
	Signal string `json:"signal,omitempty"`
}

// Publisher delivers session output and events to the controller (over MQTT).
type Publisher interface {
	PublishShellOutput(sessionID string, data []byte) error
	PublishShellEvent(ev Event) error
}

// Options configures a Manager.
type Options struct {
	DefaultShell string
	IdleTimeout  time.Duration
	MaxDuration  time.Duration
	MaxSessions  int
	Logger       *zap.Logger

	// newPTY is overridable in tests; defaults to the platform startPTY.
	newPTY func(shellPath string, rows, cols uint16) (ptySession, error)
}

// Manager owns the agent's live shell sessions.
type Manager struct {
	pub          Publisher
	newPTY       func(shellPath string, rows, cols uint16) (ptySession, error)
	defaultShell string
	idleTimeout  time.Duration
	maxDuration  time.Duration
	maxSessions  int
	logger       *zap.Logger

	mu       sync.Mutex
	sessions map[string]*Session
}

const defaultMaxSessions = 2

// NewManager creates a shell session manager.
func NewManager(pub Publisher, opts Options) *Manager {
	if opts.newPTY == nil {
		opts.newPTY = startPTY
	}
	if opts.DefaultShell == "" {
		opts.DefaultShell = defaultShell()
	}
	if opts.MaxSessions <= 0 {
		opts.MaxSessions = defaultMaxSessions
	}
	if opts.Logger == nil {
		opts.Logger = zap.NewNop()
	}
	return &Manager{
		pub:          pub,
		newPTY:       opts.newPTY,
		defaultShell: opts.DefaultShell,
		idleTimeout:  opts.IdleTimeout,
		maxDuration:  opts.MaxDuration,
		maxSessions:  opts.MaxSessions,
		logger:       opts.Logger,
		sessions:     map[string]*Session{},
	}
}

// Open starts a new shell session, publishing a started or error event.
func (m *Manager) Open(req OpenRequest) {
	m.mu.Lock()
	if _, exists := m.sessions[req.SessionID]; exists {
		m.mu.Unlock()
		return
	}
	if len(m.sessions) >= m.maxSessions {
		m.mu.Unlock()
		m.logger.Warn("shell session rejected: limit reached",
			zap.String("session", req.SessionID), zap.Int("max", m.maxSessions))
		m.emit(Event{SessionID: req.SessionID, Type: "error", Msg: "session limit reached"})
		return
	}
	m.mu.Unlock()

	shellPath := req.Shell
	if shellPath == "" {
		shellPath = m.defaultShell
	}

	pty, err := m.newPTY(shellPath, req.Rows, req.Cols)
	if err != nil {
		m.logger.Error("failed to start PTY", zap.String("session", req.SessionID), zap.Error(err))
		m.emit(Event{SessionID: req.SessionID, Type: "error", Msg: err.Error()})
		return
	}

	sid := req.SessionID
	sess := newSession(pty,
		func(b []byte) { _ = m.pub.PublishShellOutput(sid, b) },
		func(exitErr error) { m.onSessionExit(sid, exitErr) },
	).withLimits(m.idleTimeout, m.maxDuration)

	m.mu.Lock()
	m.sessions[sid] = sess
	m.mu.Unlock()

	sess.start()
	m.logger.Info("shell session started", zap.String("session", sid))
	m.emit(Event{SessionID: sid, Type: "started"})
}

// Input forwards keystrokes to a session's shell.
func (m *Manager) Input(sessionID string, data []byte) {
	if sess := m.get(sessionID); sess != nil {
		if _, err := sess.Write(data); err != nil {
			m.logger.Warn("shell input write failed", zap.String("session", sessionID), zap.Error(err))
		}
	}
}

// Control applies a resize/close/signal to a session.
func (m *Manager) Control(sessionID string, ctl Control) {
	sess := m.get(sessionID)
	if sess == nil {
		return
	}
	switch ctl.Type {
	case "resize":
		if err := sess.Resize(ctl.Rows, ctl.Cols); err != nil {
			m.logger.Warn("shell resize failed", zap.String("session", sessionID), zap.Error(err))
		}
	case "close":
		_ = sess.Close()
	}
}

// CloseAll terminates every live session (agent shutdown).
func (m *Manager) CloseAll() {
	m.mu.Lock()
	sessions := make([]*Session, 0, len(m.sessions))
	for _, s := range m.sessions {
		sessions = append(sessions, s)
	}
	m.mu.Unlock()
	for _, s := range sessions {
		_ = s.Close()
	}
}

func (m *Manager) onSessionExit(sessionID string, exitErr error) {
	m.mu.Lock()
	delete(m.sessions, sessionID)
	m.mu.Unlock()

	if exitErr != nil {
		m.emit(Event{SessionID: sessionID, Type: "error", Msg: exitErr.Error()})
		return
	}
	m.logger.Info("shell session ended", zap.String("session", sessionID))
	m.emit(Event{SessionID: sessionID, Type: "exited"})
}

func (m *Manager) get(sessionID string) *Session {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.sessions[sessionID]
}

func (m *Manager) emit(ev Event) {
	if err := m.pub.PublishShellEvent(ev); err != nil {
		m.logger.Warn("failed to publish shell event", zap.String("session", ev.SessionID), zap.Error(err))
	}
}
