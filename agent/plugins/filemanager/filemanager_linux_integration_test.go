//go:build integration && linux

package filemanager

import (
	"context"
	"os"
	"os/user"
	"path/filepath"
	"testing"

	"github.com/edgeguardian/agent/internal/reconciler"
	"go.uber.org/zap"
)

func integrationLogger() *zap.Logger {
	logger, _ := zap.NewDevelopment()
	return logger
}

func TestLinux_FileMode_0644(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "mode-test.conf")

	fm := New(integrationLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "file",
			Name: target,
			Fields: map[string]interface{}{
				"path":    target,
				"content": "test data\n",
				"mode":    "0644",
				"owner":   "",
			},
		},
	}

	actions := fm.Reconcile(context.Background(), specs)
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}

	info, err := os.Stat(target)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if info.Mode().Perm() != 0644 {
		t.Fatalf("expected mode 0644, got %04o", info.Mode().Perm())
	}
}

func TestLinux_FileMode_0755(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "script.sh")

	fm := New(integrationLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "file",
			Name: target,
			Fields: map[string]interface{}{
				"path":    target,
				"content": "#!/bin/bash\necho hello\n",
				"mode":    "0755",
				"owner":   "",
			},
		},
	}

	actions := fm.Reconcile(context.Background(), specs)
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}

	info, err := os.Stat(target)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if info.Mode().Perm() != 0755 {
		t.Fatalf("expected mode 0755, got %04o", info.Mode().Perm())
	}
}

func TestLinux_FileMode_0600(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "secret.key")

	fm := New(integrationLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "file",
			Name: target,
			Fields: map[string]interface{}{
				"path":    target,
				"content": "supersecret",
				"mode":    "0600",
				"owner":   "",
			},
		},
	}

	actions := fm.Reconcile(context.Background(), specs)
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}

	info, err := os.Stat(target)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if info.Mode().Perm() != 0600 {
		t.Fatalf("expected mode 0600, got %04o", info.Mode().Perm())
	}
}

func TestLinux_ModeChange_UpdatesExisting(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "change-mode.conf")

	// Create file with 0644 first.
	if err := os.WriteFile(target, []byte("data"), 0644); err != nil {
		t.Fatal(err)
	}

	fm := New(integrationLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "file",
			Name: target,
			Fields: map[string]interface{}{
				"path":    target,
				"content": "data",
				"mode":    "0600",
				"owner":   "",
			},
		},
	}

	actions := fm.Reconcile(context.Background(), specs)
	if actions[0].ActionType != reconciler.ActionUpdate {
		t.Fatalf("expected ActionUpdate for mode change, got %s", actions[0].ActionType)
	}
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}

	info, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0600 {
		t.Fatalf("expected mode 0600 after update, got %04o", info.Mode().Perm())
	}
}

func TestLinux_Chown_CurrentUser(t *testing.T) {
	// Get current user for a valid owner string.
	u, err := user.Current()
	if err != nil {
		t.Skipf("cannot get current user: %v", err)
	}

	g, err := user.LookupGroupId(u.Gid)
	if err != nil {
		t.Skipf("cannot get current group: %v", err)
	}

	owner := u.Username + ":" + g.Name

	dir := t.TempDir()
	target := filepath.Join(dir, "owned.conf")

	fm := New(integrationLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "file",
			Name: target,
			Fields: map[string]interface{}{
				"path":    target,
				"content": "owned content",
				"mode":    "0644",
				"owner":   owner,
			},
		},
	}

	actions := fm.Reconcile(context.Background(), specs)
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}
	if actions[0].ActionType != reconciler.ActionCreate {
		t.Fatalf("expected ActionCreate, got %s", actions[0].ActionType)
	}
}

func TestLinux_AtomicWrite_SurvivesConcurrentRead(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "atomic.conf")

	// Write initial content.
	if err := os.WriteFile(target, []byte("initial"), 0644); err != nil {
		t.Fatal(err)
	}

	fm := New(integrationLogger())

	// Update the file - should be atomic (temp + rename).
	specs := []reconciler.ResourceSpec{
		{
			Kind: "file",
			Name: target,
			Fields: map[string]interface{}{
				"path":    target,
				"content": "updated via atomic write",
				"mode":    "",
				"owner":   "",
			},
		},
	}

	actions := fm.Reconcile(context.Background(), specs)
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}

	content, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "updated via atomic write" {
		t.Fatalf("expected 'updated via atomic write', got %q", string(content))
	}

	// Verify no temp files left behind.
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if e.Name() != "atomic.conf" {
			t.Fatalf("temp file not cleaned up: %s", e.Name())
		}
	}
}

func TestLinux_CreateNestedDirs_WithMode(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "deep", "nested", "path", "config.yaml")

	fm := New(integrationLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "file",
			Name: target,
			Fields: map[string]interface{}{
				"path":    target,
				"content": "key: value\n",
				"mode":    "0644",
				"owner":   "",
			},
		},
	}

	actions := fm.Reconcile(context.Background(), specs)
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}

	content, err := os.ReadFile(target)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(content) != "key: value\n" {
		t.Fatalf("content mismatch: %q", string(content))
	}

	info, _ := os.Stat(target)
	if info.Mode().Perm() != 0644 {
		t.Fatalf("expected mode 0644, got %04o", info.Mode().Perm())
	}
}
