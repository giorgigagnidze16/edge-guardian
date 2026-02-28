package filemanager

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"github.com/edgeguardian/agent/internal/reconciler"
	"go.uber.org/zap"
)

func testLogger() *zap.Logger {
	logger, _ := zap.NewDevelopment()
	return logger
}

func TestFileManager_Name(t *testing.T) {
	fm := New(testLogger())
	if fm.Name() != "file_manager" {
		t.Fatalf("expected 'file_manager', got %q", fm.Name())
	}
}

func TestFileManager_CanHandle(t *testing.T) {
	fm := New(testLogger())
	if !fm.CanHandle("file") {
		t.Fatal("expected CanHandle('file') to be true")
	}
	if fm.CanHandle("service") {
		t.Fatal("expected CanHandle('service') to be false")
	}
}

func TestFileManager_CreateFile(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "test.conf")

	fm := New(testLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "file",
			Name: target,
			Fields: map[string]interface{}{
				"path":    target,
				"content": "hello world\n",
				"mode":    "0644",
				"owner":   "",
			},
		},
	}

	actions := fm.Reconcile(context.Background(), specs)
	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d", len(actions))
	}
	if actions[0].ActionType != reconciler.ActionCreate {
		t.Fatalf("expected ActionCreate, got %s", actions[0].ActionType)
	}
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}

	// Verify file was created with correct content.
	content, err := os.ReadFile(target)
	if err != nil {
		t.Fatalf("read created file: %v", err)
	}
	if string(content) != "hello world\n" {
		t.Fatalf("expected 'hello world\\n', got %q", string(content))
	}
}

func TestFileManager_UpdateFile(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "update.conf")

	// Create existing file with different content.
	if err := os.WriteFile(target, []byte("old content"), 0644); err != nil {
		t.Fatal(err)
	}

	fm := New(testLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "file",
			Name: target,
			Fields: map[string]interface{}{
				"path":    target,
				"content": "new content",
				"mode":    "0644",
				"owner":   "",
			},
		},
	}

	actions := fm.Reconcile(context.Background(), specs)
	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d", len(actions))
	}
	if actions[0].ActionType != reconciler.ActionUpdate {
		t.Fatalf("expected ActionUpdate, got %s", actions[0].ActionType)
	}

	content, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "new content" {
		t.Fatalf("expected 'new content', got %q", string(content))
	}
}

func TestFileManager_NoopWhenConverged(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "converged.conf")

	// Create file with desired content and mode.
	if err := os.WriteFile(target, []byte("correct"), 0644); err != nil {
		t.Fatal(err)
	}

	fm := New(testLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "file",
			Name: target,
			Fields: map[string]interface{}{
				"path":    target,
				"content": "correct",
				"mode":    "0644",
				"owner":   "",
			},
		},
	}

	actions := fm.Reconcile(context.Background(), specs)
	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d", len(actions))
	}
	if actions[0].ActionType != reconciler.ActionNoop {
		t.Fatalf("expected ActionNoop, got %s", actions[0].ActionType)
	}
}

func TestFileManager_CreateParentDirs(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "sub", "dir", "file.txt")

	fm := New(testLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind: "file",
			Name: target,
			Fields: map[string]interface{}{
				"path":    target,
				"content": "nested",
				"mode":    "",
				"owner":   "",
			},
		},
	}

	actions := fm.Reconcile(context.Background(), specs)
	if actions[0].ActionType != reconciler.ActionCreate {
		t.Fatalf("expected ActionCreate, got %s", actions[0].ActionType)
	}
	if actions[0].Error != "" {
		t.Fatalf("unexpected error: %s", actions[0].Error)
	}

	content, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(content) != "nested" {
		t.Fatalf("expected 'nested', got %q", string(content))
	}
}

func TestFileManager_EmptyPath(t *testing.T) {
	fm := New(testLogger())
	specs := []reconciler.ResourceSpec{
		{
			Kind:   "file",
			Name:   "",
			Fields: map[string]interface{}{"path": "", "content": "x"},
		},
	}

	actions := fm.Reconcile(context.Background(), specs)
	if actions[0].Error == "" {
		t.Fatal("expected error for empty path")
	}
}

func TestFileManager_ContextCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel() // Cancel immediately.

	fm := New(testLogger())
	specs := []reconciler.ResourceSpec{
		{Kind: "file", Name: "/tmp/test", Fields: map[string]interface{}{"path": "/tmp/test"}},
	}

	actions := fm.Reconcile(ctx, specs)
	if len(actions) != 1 {
		t.Fatalf("expected 1 action, got %d", len(actions))
	}
	if actions[0].ActionType != reconciler.ActionSkipped {
		t.Fatalf("expected ActionSkipped, got %s", actions[0].ActionType)
	}
}

func TestFileManager_MultipleFiles(t *testing.T) {
	dir := t.TempDir()

	fm := New(testLogger())
	var specs []reconciler.ResourceSpec
	for i := 0; i < 3; i++ {
		path := filepath.Join(dir, filepath.Base(t.Name()), string(rune('a'+i))+".txt")
		specs = append(specs, reconciler.ResourceSpec{
			Kind: "file",
			Name: path,
			Fields: map[string]interface{}{
				"path":    path,
				"content": "file content",
				"mode":    "",
				"owner":   "",
			},
		})
	}

	actions := fm.Reconcile(context.Background(), specs)
	if len(actions) != 3 {
		t.Fatalf("expected 3 actions, got %d", len(actions))
	}
	for _, a := range actions {
		if a.ActionType != reconciler.ActionCreate {
			t.Fatalf("expected ActionCreate, got %s for %s", a.ActionType, a.Resource)
		}
	}
}
