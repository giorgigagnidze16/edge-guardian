package main

import (
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"time"
)

// Watchdog is a minimal supervisor for the EdgeGuardian agent.
// It restarts the agent if it crashes, with exponential backoff.
// Exit code 42 from the agent signals an OTA binary swap.

const (
	maxBackoff     = 5 * time.Minute
	initialBackoff = 1 * time.Second
	otaExitCode    = 42

	// Rollback if the agent crashes this many times within the window.
	crashThreshold = 3
	crashWindow    = 5 * time.Minute
)

func main() {
	fmt.Println("EdgeGuardian watchdog starting")

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, platformSignals()...)

	backoff := initialBackoff
	var crashTimes []time.Time

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
		exitCode := cmd.ProcessState.ExitCode()

		if exitCode == otaExitCode {
			fmt.Println("agent requested OTA binary swap (exit code 42)")
			if swapErr := swapBinary(); swapErr != nil {
				fmt.Printf("OTA swap failed: %v\n", swapErr)
			} else {
				fmt.Println("OTA binary swapped successfully")
			}
			backoff = initialBackoff
			continue
		}

		if err != nil {
			fmt.Printf("agent exited with error (code %d): %v\n", exitCode, err)

			// Track crash times for rollback detection
			now := time.Now()
			crashTimes = append(crashTimes, now)
			// Prune old crash times outside the window
			cutoff := now.Add(-crashWindow)
			pruned := crashTimes[:0]
			for _, t := range crashTimes {
				if t.After(cutoff) {
					pruned = append(pruned, t)
				}
			}
			crashTimes = pruned

			if len(crashTimes) >= crashThreshold {
				fmt.Printf("agent crashed %d times in %v, attempting rollback\n",
					len(crashTimes), crashWindow)
				if rbErr := rollback(); rbErr != nil {
					fmt.Printf("rollback failed: %v\n", rbErr)
				} else {
					fmt.Println("rollback completed, clearing crash counter")
					crashTimes = nil
				}
			}

			fmt.Printf("restarting in %v\n", backoff)
			time.Sleep(backoff)

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

// swapBinary replaces the active agent binary with the staging binary.
func swapBinary() error {
	dir := filepath.Dir(defaultAgentBinary)
	stagingPath := filepath.Join(dir, "..", "data", "agent-staging")

	// Check if staging binary exists
	if _, err := os.Stat(stagingPath); os.IsNotExist(err) {
		// Try the data dir default location
		stagingPath = filepath.Join(defaultDataDir, "agent-staging")
		if _, err := os.Stat(stagingPath); os.IsNotExist(err) {
			return fmt.Errorf("staging binary not found at %s", stagingPath)
		}
	}

	// Backup current binary
	backupPath := defaultAgentBinary + ".bak"
	if err := os.Rename(defaultAgentBinary, backupPath); err != nil {
		return fmt.Errorf("backup current binary: %w", err)
	}

	// Move staging to active
	if err := os.Rename(stagingPath, defaultAgentBinary); err != nil {
		// Restore backup on failure
		os.Rename(backupPath, defaultAgentBinary)
		return fmt.Errorf("swap staging binary: %w", err)
	}

	fmt.Printf("binary swapped: %s -> %s (backup: %s)\n",
		stagingPath, defaultAgentBinary, backupPath)
	return nil
}

// rollback restores the previous binary from backup.
func rollback() error {
	backupPath := defaultAgentBinary + ".bak"
	if _, err := os.Stat(backupPath); os.IsNotExist(err) {
		return fmt.Errorf("no backup binary found at %s", backupPath)
	}

	if err := os.Rename(defaultAgentBinary, defaultAgentBinary+".failed"); err != nil {
		// Non-fatal, continue with rollback
		fmt.Printf("warning: could not rename failed binary: %v\n", err)
	}

	if err := os.Rename(backupPath, defaultAgentBinary); err != nil {
		return fmt.Errorf("restore backup: %w", err)
	}

	fmt.Println("rolled back to previous agent binary")
	return nil
}
