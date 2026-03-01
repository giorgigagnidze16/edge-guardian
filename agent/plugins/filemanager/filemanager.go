// Package filemanager implements a reconciler plugin that manages files on disk.
// It ensures files exist with the correct content, permissions, and ownership
// as declared in the device manifest. Writes are atomic (temp file + rename)
// to prevent partial writes on power loss.
package filemanager

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/edgeguardian/agent/internal/reconciler"
	"go.uber.org/zap"
)

// FileManager reconciles file resources on the local filesystem.
type FileManager struct {
	logger *zap.Logger
}

// New creates a FileManager plugin.
func New(logger *zap.Logger) *FileManager {
	return &FileManager{logger: logger}
}

// Name returns the plugin identifier.
func (fm *FileManager) Name() string {
	return "file_manager"
}

// CanHandle returns true for "file" resources.
func (fm *FileManager) CanHandle(kind string) bool {
	return kind == "file"
}

// Reconcile compares desired files against actual files on disk and applies changes.
func (fm *FileManager) Reconcile(ctx context.Context, specs []reconciler.ResourceSpec) []reconciler.Action {
	var actions []reconciler.Action

	for _, spec := range specs {
		select {
		case <-ctx.Done():
			actions = append(actions, reconciler.Action{
				Plugin:      fm.Name(),
				Resource:    spec.Name,
				ActionType:  reconciler.ActionSkipped,
				Description: "context cancelled",
			})
			return actions
		default:
		}

		action := fm.reconcileFile(spec)
		actions = append(actions, action)
	}

	return actions
}

func (fm *FileManager) reconcileFile(spec reconciler.ResourceSpec) reconciler.Action {
	path, _ := spec.Fields["path"].(string)
	desiredContent, _ := spec.Fields["content"].(string)
	desiredMode, _ := spec.Fields["mode"].(string)
	desiredOwner, _ := spec.Fields["owner"].(string)

	if path == "" {
		return reconciler.Action{
			Plugin:     fm.Name(),
			Resource:   spec.Name,
			ActionType: reconciler.ActionSkipped,
			Error:      "empty file path",
		}
	}

	// Read existing file content.
	existingContent, err := os.ReadFile(path)
	if err != nil && !os.IsNotExist(err) {
		return reconciler.Action{
			Plugin:     fm.Name(),
			Resource:   path,
			ActionType: reconciler.ActionSkipped,
			Error:      fmt.Sprintf("read existing file: %v", err),
		}
	}

	fileExists := err == nil
	contentMatches := fileExists && string(existingContent) == desiredContent
	modeMatches := fm.checkMode(path, desiredMode)
	ownerMatches := fm.checkOwner(path, desiredOwner)

	// If everything matches, no action needed.
	if contentMatches && modeMatches && ownerMatches {
		return reconciler.Action{
			Plugin:      fm.Name(),
			Resource:    path,
			ActionType:  reconciler.ActionNoop,
			Description: "file already matches desired state",
		}
	}

	// Determine action type.
	actionType := reconciler.ActionUpdate
	if !fileExists {
		actionType = reconciler.ActionCreate
	}

	// Ensure parent directory exists.
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return reconciler.Action{
			Plugin:     fm.Name(),
			Resource:   path,
			ActionType: actionType,
			Error:      fmt.Sprintf("create parent directory: %v", err),
		}
	}

	// Atomic write: write to temp file, then rename.
	if !contentMatches {
		if err := fm.atomicWrite(path, []byte(desiredContent)); err != nil {
			return reconciler.Action{
				Plugin:     fm.Name(),
				Resource:   path,
				ActionType: actionType,
				Error:      fmt.Sprintf("atomic write: %v", err),
			}
		}
	}

	// Set permissions.
	if desiredMode != "" && !modeMatches {
		if err := fm.setMode(path, desiredMode); err != nil {
			return reconciler.Action{
				Plugin:     fm.Name(),
				Resource:   path,
				ActionType: actionType,
				Error:      fmt.Sprintf("set mode: %v", err),
			}
		}
	}

	// Set ownership (Linux only, requires root).
	if desiredOwner != "" && !ownerMatches {
		if err := fm.setOwner(path, desiredOwner); err != nil {
			fm.logger.Warn("failed to set owner (may need root)",
				zap.String("path", path),
				zap.String("owner", desiredOwner),
				zap.Error(err),
			)
			// Ownership failure is non-fatal — log warning but report success.
		}
	}

	desc := "created file"
	if actionType == reconciler.ActionUpdate {
		var changes []string
		if !contentMatches {
			changes = append(changes, "content")
		}
		if !modeMatches {
			changes = append(changes, "mode")
		}
		if !ownerMatches {
			changes = append(changes, "owner")
		}
		desc = "updated " + strings.Join(changes, ", ")
	}

	return reconciler.Action{
		Plugin:      fm.Name(),
		Resource:    path,
		ActionType:  actionType,
		Description: desc,
	}
}

// atomicWrite writes data to a temp file in the same directory, then renames it
// to the target path. This prevents partial writes on crash/power loss.
func (fm *FileManager) atomicWrite(path string, data []byte) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".eg-tmp-*")
	if err != nil {
		return fmt.Errorf("create temp file: %w", err)
	}
	tmpPath := tmp.Name()

	// Clean up temp file on any error.
	defer func() {
		if tmpPath != "" {
			os.Remove(tmpPath)
		}
	}()

	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return fmt.Errorf("write temp file: %w", err)
	}

	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return fmt.Errorf("sync temp file: %w", err)
	}

	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close temp file: %w", err)
	}

	if err := os.Rename(tmpPath, path); err != nil {
		return fmt.Errorf("rename temp to target: %w", err)
	}

	// Rename succeeded, prevent cleanup from removing the target file.
	tmpPath = ""
	return nil
}

// checkMode returns true if the file's permissions match the desired mode string.
func (fm *FileManager) checkMode(path, desiredMode string) bool {
	if desiredMode == "" {
		return true
	}

	info, err := os.Stat(path)
	if err != nil {
		return false
	}

	mode, err := strconv.ParseUint(desiredMode, 8, 32)
	if err != nil {
		return false
	}

	return info.Mode().Perm() == os.FileMode(mode)
}

// setMode applies the desired mode to the file.
func (fm *FileManager) setMode(path, desiredMode string) error {
	mode, err := strconv.ParseUint(desiredMode, 8, 32)
	if err != nil {
		return fmt.Errorf("parse mode %q: %w", desiredMode, err)
	}
	return os.Chmod(path, os.FileMode(mode))
}

// checkOwner verifies the file's ownership matches "user:group".
// On non-Linux systems, this always returns true (ownership is a no-op).
func (fm *FileManager) checkOwner(path, desiredOwner string) bool {
	if desiredOwner == "" {
		return true
	}
	return checkOwnerPlatform(path, desiredOwner)
}

// setOwner sets the file's ownership to "user:group" using chown.
// Linux and macOS only, requires root.
func (fm *FileManager) setOwner(path, owner string) error {
	parts := strings.SplitN(owner, ":", 2)
	if len(parts) != 2 {
		return fmt.Errorf("invalid owner format %q (expected user:group)", owner)
	}

	// Use the chown command for simplicity and portability.
	cmd := exec.Command("chown", owner, path)
	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("chown %s %s: %s: %w", owner, path, string(output), err)
	}
	return nil
}
