package shell

import (
	"sync"
	"testing"
	"time"
)

type fakePublisher struct {
	mu      sync.Mutex
	outputs map[string][]byte
	events  []Event
}

func newFakePublisher() *fakePublisher {
	return &fakePublisher{outputs: map[string][]byte{}}
}

func (f *fakePublisher) PublishShellOutput(sessionID string, data []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.outputs[sessionID] = append(f.outputs[sessionID], data...)
	return nil
}

func (f *fakePublisher) PublishShellEvent(ev Event) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.events = append(f.events, ev)
	return nil
}

func (f *fakePublisher) output(sessionID string) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return string(f.outputs[sessionID])
}

func (f *fakePublisher) eventsOfType(t string) []Event {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []Event
	for _, e := range f.events {
		if e.Type == t {
			out = append(out, e)
		}
	}
	return out
}

// recordingFactory hands out fakePTYs and remembers them so tests can drive I/O.
type recordingFactory struct {
	mu   sync.Mutex
	ptys map[string]*fakePTY // keyed by the shell path? no — by creation order
	list []*fakePTY
}

func newRecordingFactory() *recordingFactory {
	return &recordingFactory{ptys: map[string]*fakePTY{}}
}

func (r *recordingFactory) make(_ string, _, _ uint16) (ptySession, error) {
	p := newFakePTY()
	r.mu.Lock()
	r.list = append(r.list, p)
	r.mu.Unlock()
	return p, nil
}

func (r *recordingFactory) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.list)
}

func (r *recordingFactory) last() *fakePTY {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.list[len(r.list)-1]
}

func newTestManager(pub Publisher, fac *recordingFactory, maxSessions int) *Manager {
	return NewManager(pub, Options{
		DefaultShell: "/bin/sh",
		MaxSessions:  maxSessions,
		newPTY:       fac.make,
	})
}

func TestManager_OpenPublishesStartedEvent(t *testing.T) {
	pub := newFakePublisher()
	fac := newRecordingFactory()
	m := newTestManager(pub, fac, 4)

	m.Open(OpenRequest{SessionID: "s1", Rows: 24, Cols: 80})
	defer m.CloseAll()

	waitFor(t, func() bool { return len(pub.eventsOfType("started")) == 1 }, time.Second)
	if fac.count() != 1 {
		t.Fatalf("expected 1 PTY created, got %d", fac.count())
	}
}

func TestManager_PublishesPtyOutputForSession(t *testing.T) {
	pub := newFakePublisher()
	fac := newRecordingFactory()
	m := newTestManager(pub, fac, 4)

	m.Open(OpenRequest{SessionID: "s1", Rows: 24, Cols: 80})
	defer m.CloseAll()
	waitFor(t, func() bool { return fac.count() == 1 }, time.Second)

	fac.last().pushOutput([]byte("device-output"))
	waitFor(t, func() bool { return pub.output("s1") == "device-output" }, time.Second)
}

func TestManager_InputForwardedToPty(t *testing.T) {
	pub := newFakePublisher()
	fac := newRecordingFactory()
	m := newTestManager(pub, fac, 4)

	m.Open(OpenRequest{SessionID: "s1", Rows: 24, Cols: 80})
	defer m.CloseAll()
	waitFor(t, func() bool { return fac.count() == 1 }, time.Second)

	m.Input("s1", []byte("whoami\n"))
	waitFor(t, func() bool { return fac.last().stdinString() == "whoami\n" }, time.Second)
}

func TestManager_ControlCloseEndsSessionWithExitedEvent(t *testing.T) {
	pub := newFakePublisher()
	fac := newRecordingFactory()
	m := newTestManager(pub, fac, 4)

	m.Open(OpenRequest{SessionID: "s1", Rows: 24, Cols: 80})
	waitFor(t, func() bool { return fac.count() == 1 }, time.Second)

	m.Control("s1", Control{Type: "close"})
	waitFor(t, func() bool { return len(pub.eventsOfType("exited")) == 1 }, time.Second)
}

func TestManager_ControlResizeAppliedToPty(t *testing.T) {
	pub := newFakePublisher()
	fac := newRecordingFactory()
	m := newTestManager(pub, fac, 4)

	m.Open(OpenRequest{SessionID: "s1", Rows: 24, Cols: 80})
	defer m.CloseAll()
	waitFor(t, func() bool { return fac.count() == 1 }, time.Second)

	m.Control("s1", Control{Type: "resize", Rows: 50, Cols: 200})
	p := fac.last()
	waitFor(t, func() bool {
		p.mu.Lock()
		defer p.mu.Unlock()
		return len(p.resizes) == 1 && p.resizes[0] == [2]uint16{50, 200}
	}, time.Second)
}

func TestManager_EnforcesMaxSessions(t *testing.T) {
	pub := newFakePublisher()
	fac := newRecordingFactory()
	m := newTestManager(pub, fac, 1)
	defer m.CloseAll()

	m.Open(OpenRequest{SessionID: "s1", Rows: 24, Cols: 80})
	waitFor(t, func() bool { return len(pub.eventsOfType("started")) == 1 }, time.Second)

	m.Open(OpenRequest{SessionID: "s2", Rows: 24, Cols: 80})
	waitFor(t, func() bool { return len(pub.eventsOfType("error")) == 1 }, time.Second)

	if fac.count() != 1 {
		t.Fatalf("expected cap to prevent 2nd PTY, got %d PTYs", fac.count())
	}
}
