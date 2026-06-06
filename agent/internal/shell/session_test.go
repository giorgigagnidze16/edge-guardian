package shell

import (
	"bytes"
	"io"
	"sync"
	"testing"
	"time"
)

// fakePTY is an in-memory ptySession: the test drives "shell output" through a
// pipe and inspects bytes written to "stdin".
type fakePTY struct {
	outR *io.PipeReader
	outW *io.PipeWriter

	mu      sync.Mutex
	stdin   bytes.Buffer
	resizes [][2]uint16
}

func newFakePTY() *fakePTY {
	r, w := io.Pipe()
	return &fakePTY{outR: r, outW: w}
}

func (f *fakePTY) Read(b []byte) (int, error) { return f.outR.Read(b) }
func (f *fakePTY) Write(b []byte) (int, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.stdin.Write(b)
}
func (f *fakePTY) Resize(rows, cols uint16) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.resizes = append(f.resizes, [2]uint16{rows, cols})
	return nil
}
func (f *fakePTY) Close() error { return f.outW.Close() }

func (f *fakePTY) pushOutput(b []byte) { _, _ = f.outW.Write(b) }
func (f *fakePTY) finishOutput()       { _ = f.outW.Close() }
func (f *fakePTY) stdinString() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.stdin.String()
}

func waitFor(t *testing.T, cond func() bool, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("condition not met within %v", timeout)
}

func TestSession_StreamsPtyOutputToSink(t *testing.T) {
	fake := newFakePTY()
	var mu sync.Mutex
	var got []byte
	s := newSession(fake, func(b []byte) {
		mu.Lock()
		got = append(got, b...)
		mu.Unlock()
	}, func(error) {})
	s.start()
	defer s.Close()

	fake.pushOutput([]byte("hello world"))

	waitFor(t, func() bool {
		mu.Lock()
		defer mu.Unlock()
		return string(got) == "hello world"
	}, time.Second)
}

func TestSession_WriteForwardsToPty(t *testing.T) {
	fake := newFakePTY()
	s := newSession(fake, func([]byte) {}, func(error) {})
	s.start()
	defer s.Close()

	if _, err := s.Write([]byte("ls -la\n")); err != nil {
		t.Fatalf("write: %v", err)
	}
	waitFor(t, func() bool { return fake.stdinString() == "ls -la\n" }, time.Second)
}

func TestSession_CallsOnExitWhenShellCloses(t *testing.T) {
	fake := newFakePTY()
	exited := make(chan error, 1)
	s := newSession(fake, func([]byte) {}, func(err error) { exited <- err })
	s.start()

	fake.finishOutput() // shell exits -> PTY read returns EOF

	select {
	case <-exited:
	case <-time.After(time.Second):
		t.Fatal("onExit was not called after shell closed")
	}
}

func TestSession_OnExitCalledOnceOnClose(t *testing.T) {
	fake := newFakePTY()
	var count int
	var mu sync.Mutex
	s := newSession(fake, func([]byte) {}, func(error) {
		mu.Lock()
		count++
		mu.Unlock()
	})
	s.start()

	fake.finishOutput()
	waitFor(t, func() bool { mu.Lock(); defer mu.Unlock(); return count == 1 }, time.Second)
	_ = s.Close()

	time.Sleep(50 * time.Millisecond)
	mu.Lock()
	defer mu.Unlock()
	if count != 1 {
		t.Fatalf("onExit called %d times, want exactly 1", count)
	}
}
