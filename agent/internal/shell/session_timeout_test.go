package shell

import (
	"testing"
	"time"
)

func TestSession_ClosesOnIdleTimeout(t *testing.T) {
	fake := newFakePTY()
	exited := make(chan error, 1)
	s := newSession(fake, func([]byte) {}, func(err error) { exited <- err }).
		withLimits(60*time.Millisecond, 0)
	s.start()

	select {
	case <-exited:
	case <-time.After(time.Second):
		t.Fatal("session did not close on idle timeout")
	}
}

func TestSession_ClosesOnMaxDuration(t *testing.T) {
	fake := newFakePTY()
	exited := make(chan error, 1)
	s := newSession(fake, func([]byte) {}, func(err error) { exited <- err }).
		withLimits(0, 60*time.Millisecond)
	s.start()

	// Keep "input" flowing so idle is not the cause (idle is disabled here anyway).
	go func() {
		ticker := time.NewTicker(15 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-exited:
				return
			case <-ticker.C:
				_, _ = s.Write([]byte("x"))
			}
		}
	}()

	select {
	case <-exited:
	case <-time.After(time.Second):
		t.Fatal("session did not close on max duration")
	}
}

func TestSession_IdleTimerResetsOnInput(t *testing.T) {
	fake := newFakePTY()
	exited := make(chan struct{})
	s := newSession(fake, func([]byte) {}, func(error) { close(exited) }).
		withLimits(150*time.Millisecond, 0)
	s.start()
	defer s.Close()

	// Write at 100ms keeps the session alive past the original 150ms deadline.
	time.Sleep(100 * time.Millisecond)
	_, _ = s.Write([]byte("keepalive"))

	// At 200ms: without a reset it would have closed at 150ms; with the reset
	// the new deadline is ~250ms, so it must still be alive.
	select {
	case <-exited:
		t.Fatal("idle timer did not reset on input; session closed early")
	case <-time.After(100 * time.Millisecond):
	}
}
