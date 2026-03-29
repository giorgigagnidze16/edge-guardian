package commands

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"time"

	"github.com/edgeguardian/agent/internal/model"
)

const maxOutputBytes = 4096

// ExecuteScript runs a ScriptSpec and returns a CommandResult.
func ExecuteScript(ctx context.Context, spec *model.ScriptSpec, deviceID string) *model.CommandResult {
	start := time.Now()
	result := &model.CommandResult{
		DeviceID:  deviceID,
		Timestamp: start,
	}

	if spec.Inline == "" {
		result.Status = "failed"
		result.ErrorMessage = "empty script"
		return result
	}

	// Apply script-level timeout if set and no parent context timeout.
	if spec.Timeout > 0 {
		if _, hasDeadline := ctx.Deadline(); !hasDeadline {
			var cancel context.CancelFunc
			ctx, cancel = context.WithTimeout(ctx, time.Duration(spec.Timeout)*time.Second)
			defer cancel()
		}
	}

	interpreter, args := resolveInterpreter(spec.Interpreter)

	// Write inline script to temp file.
	tmpFile, err := os.CreateTemp("", "eg-script-*")
	if err != nil {
		result.Status = "failed"
		result.ErrorMessage = fmt.Sprintf("create temp script: %v", err)
		result.DurationMs = time.Since(start).Milliseconds()
		return result
	}
	defer os.Remove(tmpFile.Name())

	if _, err := tmpFile.WriteString(spec.Inline); err != nil {
		tmpFile.Close()
		result.Status = "failed"
		result.ErrorMessage = fmt.Sprintf("write temp script: %v", err)
		result.DurationMs = time.Since(start).Milliseconds()
		return result
	}
	tmpFile.Close()
	os.Chmod(tmpFile.Name(), 0700)

	cmdArgs := append(args, tmpFile.Name())
	cmd := exec.CommandContext(ctx, interpreter, cmdArgs...)

	// Set working directory.
	if spec.WorkDir != "" {
		cmd.Dir = spec.WorkDir
	}

	// Set environment variables.
	if len(spec.Env) > 0 {
		cmd.Env = os.Environ()
		for k, v := range spec.Env {
			cmd.Env = append(cmd.Env, k+"="+v)
		}
	}

	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	err = cmd.Run()
	result.DurationMs = time.Since(start).Milliseconds()
	result.Stdout = truncate(stdout.String(), maxOutputBytes)
	result.Stderr = truncate(stderr.String(), maxOutputBytes)

	if err != nil {
		if ctx.Err() == context.DeadlineExceeded {
			result.Status = "timeout"
			result.ErrorMessage = "script execution timed out"
			result.ExitCode = -1
		} else if exitErr, ok := err.(*exec.ExitError); ok {
			result.Status = "failed"
			result.ExitCode = exitErr.ExitCode()
			result.ErrorMessage = fmt.Sprintf("exit code %d", exitErr.ExitCode())
		} else {
			result.Status = "failed"
			result.ErrorMessage = err.Error()
			result.ExitCode = -1
		}
	} else {
		result.Status = "success"
		result.ExitCode = 0
	}

	return result
}

func resolveInterpreter(interpreter string) (string, []string) {
	if interpreter != "" {
		switch interpreter {
		case "bash":
			return "/bin/bash", nil
		case "sh":
			return "/bin/sh", nil
		case "python3":
			return "python3", nil
		case "powershell":
			return "powershell.exe", []string{"-File"}
		default:
			return interpreter, nil
		}
	}

	switch runtime.GOOS {
	case "windows":
		return "powershell.exe", []string{"-File"}
	default:
		return "/bin/sh", nil
	}
}

func truncate(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen] + "\n... (truncated)"
}
