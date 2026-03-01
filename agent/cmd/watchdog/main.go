package main

import (
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"time"
)

// Watchdog is a minimal (<500KB) supervisor for the EdgeGuardian agent.
// It restarts the agent if it crashes, with exponential backoff.
// Full implementation in Phase 4; this is a placeholder skeleton.

const (
	maxBackoff     = 5 * time.Minute
	initialBackoff = 1 * time.Second
)

func main() {
	fmt.Println("EdgeGuardian watchdog starting")

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, platformSignals()...)

	backoff := initialBackoff

	for {
		select {
		case sig := <-sigCh:
			fmt.Printf("watchdog received %s, exiting\n", sig)
			return
		default:
		}

		fmt.Printf("starting agent: %s --config %s\n", defaultAgentBinary, defaultAgentConfig)
		cmd := exec.Command(defaultAgentBinary, "--config", defaultAgentConfig)
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr

		err := cmd.Run()
		if err != nil {
			fmt.Printf("agent exited with error: %v\n", err)
			fmt.Printf("restarting in %v\n", backoff)
			time.Sleep(backoff)

			// Exponential backoff, capped
			backoff *= 2
			if backoff > maxBackoff {
				backoff = maxBackoff
			}
		} else {
			fmt.Println("agent exited cleanly, restarting in 1s")
			backoff = initialBackoff
			time.Sleep(1 * time.Second)
		}
	}
}
