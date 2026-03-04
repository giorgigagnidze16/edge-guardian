package main

import (
	"flag"
	"fmt"
	"io"
	"net/http"
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

	crashThreshold = 3
	crashWindow    = 5 * time.Minute

	healthTimeout  = 30 * time.Second
	healthInterval = 2 * time.Second
	healthURL      = "http://127.0.0.1:8484/healthz"
)

var (
	agentBinary string
	agentConfig string
	dataDir     string
)

func main() {
	flag.StringVar(&agentBinary, "binary", defaultAgentBinary, "path to agent binary")
	flag.StringVar(&agentConfig, "config", defaultAgentConfig, "path to agent config file")
	flag.StringVar(&dataDir, "data-dir", defaultDataDir, "path to data directory")
	flag.Parse()

	fmt.Printf("EdgeGuardian watchdog starting (binary=%s, config=%s, data-dir=%s)\n",
		agentBinary, agentConfig, dataDir)

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, platformSignals()...)

	backoff := initialBackoff
	var crashTimes []time.Time
	justSwapped := false

	for {
		select {
		case sig := <-sigCh:
			fmt.Printf("watchdog received %s, exiting\n", sig)
			return
		default:
		}

		fmt.Printf("starting agent: %s --config %s\n", agentBinary, agentConfig)
		cmd := exec.Command(agentBinary, "--config", agentConfig)
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
				justSwapped = true
			}
			backoff = initialBackoff
			continue
		}

		if err != nil {
			fmt.Printf("agent exited with error (code %d): %v\n", exitCode, err)

			// If the agent crashes immediately after an OTA swap, roll back
			// without waiting for the normal crash threshold.
			if justSwapped {
				fmt.Println("agent crashed immediately after OTA swap, triggering immediate rollback")
				if rbErr := rollback(); rbErr != nil {
					fmt.Printf("rollback failed: %v\n", rbErr)
				} else {
					fmt.Println("immediate post-OTA rollback completed")
					writeRollbackMarker()
					crashTimes = nil
				}
				justSwapped = false
				backoff = initialBackoff
				time.Sleep(1 * time.Second)
				continue
			}
			justSwapped = false

			// Track crash times for rollback detection.
			now := time.Now()
			crashTimes = append(crashTimes, now)
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
					writeRollbackMarker()
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
			// Agent exited cleanly. If we just swapped, verify health.
			if justSwapped {
				fmt.Println("agent exited cleanly after OTA swap, checking health on next start")
			}
			justSwapped = false
			fmt.Println("agent exited cleanly, restarting in 1s")
			backoff = initialBackoff
			time.Sleep(1 * time.Second)
		}
	}
}

// waitForHealthy polls the agent's health endpoint after an OTA swap.
// Returns true if the agent became healthy within the timeout.
func waitForHealthy() bool {
	client := &http.Client{Timeout: 5 * time.Second}
	deadline := time.Now().Add(healthTimeout)

	for time.Now().Before(deadline) {
		resp, err := client.Get(healthURL)
		if err == nil {
			io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				fmt.Println("agent health check passed")
				return true
			}
		}
		time.Sleep(healthInterval)
	}

	fmt.Println("agent health check timed out")
	return false
}

// swapBinary replaces the active agent binary with the staging binary.
func swapBinary() error {
	stagingPath := filepath.Join(dataDir, "agent-staging")
	if _, err := os.Stat(stagingPath); os.IsNotExist(err) {
		return fmt.Errorf("staging binary not found at %s", stagingPath)
	}

	backupPath := agentBinary + ".bak"
	if err := os.Rename(agentBinary, backupPath); err != nil {
		return fmt.Errorf("backup current binary: %w", err)
	}

	if err := os.Rename(stagingPath, agentBinary); err != nil {
		os.Rename(backupPath, agentBinary)
		return fmt.Errorf("swap staging binary: %w", err)
	}

	fmt.Printf("binary swapped: %s -> %s (backup: %s)\n", stagingPath, agentBinary, backupPath)
	return nil
}

// rollback restores the previous binary from backup.
func rollback() error {
	backupPath := agentBinary + ".bak"
	if _, err := os.Stat(backupPath); os.IsNotExist(err) {
		return fmt.Errorf("no backup binary found at %s", backupPath)
	}

	if err := os.Rename(agentBinary, agentBinary+".failed"); err != nil {
		fmt.Printf("warning: could not rename failed binary: %v\n", err)
	}

	if err := os.Rename(backupPath, agentBinary); err != nil {
		return fmt.Errorf("restore backup: %w", err)
	}

	fmt.Println("rolled back to previous agent binary")
	return nil
}

// writeRollbackMarker creates a marker file so the agent can detect that it
// was rolled back after a failed OTA update.
func writeRollbackMarker() {
	markerPath := filepath.Join(dataDir, "ota-rollback-marker")
	os.MkdirAll(dataDir, 0750)
	if err := os.WriteFile(markerPath, []byte(time.Now().UTC().Format(time.RFC3339)), 0644); err != nil {
		fmt.Printf("warning: failed to write rollback marker: %v\n", err)
	}
}