package reconciler

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/edgeguardian/agent/internal/model"
	"go.uber.org/zap"
)

func testLogger() *zap.Logger {
	logger, _ := zap.NewDevelopment()
	return logger
}

// mockPlugin implements Plugin for testing reconciler orchestration.
type mockPlugin struct {
	name       string
	handles    string
	reconciled [][]ResourceSpec
	actions    []Action
	delay      time.Duration
	mu         sync.Mutex
}

func (m *mockPlugin) Name() string               { return m.name }
func (m *mockPlugin) CanHandle(kind string) bool { return kind == m.handles }

func (m *mockPlugin) Reconcile(ctx context.Context, specs []ResourceSpec) []Action {
	m.mu.Lock()
	m.reconciled = append(m.reconciled, specs)
	m.mu.Unlock()

	if m.delay > 0 {
		select {
		case <-ctx.Done():
			return []Action{{Plugin: m.name, ActionType: ActionSkipped, Error: "context cancelled"}}
		case <-time.After(m.delay):
		}
	}

	if m.actions != nil {
		return m.actions
	}

	var actions []Action
	for _, spec := range specs {
		actions = append(actions, Action{
			Plugin:      m.name,
			Resource:    spec.Name,
			ActionType:  ActionNoop,
			Description: "already converged",
		})
	}
	return actions
}

func (m *mockPlugin) callCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.reconciled)
}

func TestReconciler_New(t *testing.T) {
	r := New(30, testLogger())
	if r.interval != 30*time.Second {
		t.Fatalf("expected interval=30s, got %v", r.interval)
	}
	if r.Status() != "converged" {
		t.Fatalf("expected initial status=converged, got %q", r.Status())
	}
}

func TestReconciler_RegisterPlugin(t *testing.T) {
	r := New(30, testLogger())
	p := &mockPlugin{name: "test_plugin", handles: "file"}
	r.RegisterPlugin(p)

	if len(r.plugins) != 1 {
		t.Fatalf("expected 1 plugin, got %d", len(r.plugins))
	}
}

func TestReconcile_NoDesiredState(t *testing.T) {
	r := New(1, testLogger())
	p := &mockPlugin{name: "fp", handles: "file"}
	r.RegisterPlugin(p)

	ctx, cancel := context.WithCancel(context.Background())
	// Run for a short time to trigger at least one reconcile cycle.
	go func() {
		time.Sleep(100 * time.Millisecond)
		cancel()
	}()
	r.Run(ctx)

	if p.callCount() != 0 {
		t.Fatalf("expected 0 plugin calls with nil desired state, got %d", p.callCount())
	}
	if r.Status() != "converged" {
		t.Fatalf("expected status=converged, got %q", r.Status())
	}
}

func TestReconcile_EmptyManifest(t *testing.T) {
	r := New(1, testLogger())
	p := &mockPlugin{name: "fp", handles: "file"}
	r.RegisterPlugin(p)
	r.SetDesiredState(&model.DeviceManifest{
		APIVersion: "edgeguardian/v1",
		Kind:       "DeviceManifest",
		Version:    1,
	})

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(100 * time.Millisecond)
		cancel()
	}()
	r.Run(ctx)

	if p.callCount() != 0 {
		t.Fatalf("expected 0 plugin calls for empty manifest, got %d", p.callCount())
	}
}

func TestReconcile_DispatchesToCorrectPlugin(t *testing.T) {
	r := New(1, testLogger())
	filePlug := &mockPlugin{name: "fp", handles: "file"}
	svcPlug := &mockPlugin{name: "sp", handles: "service"}
	r.RegisterPlugin(filePlug)
	r.RegisterPlugin(svcPlug)

	r.SetDesiredState(&model.DeviceManifest{
		Version: 1,
		Spec: model.ManifestSpec{
			Files:    []model.FileResource{{Path: "/etc/app.conf", Content: "data"}},
			Services: []model.ServiceResource{{Name: "nginx", State: "running"}},
		},
	})

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(100 * time.Millisecond)
		cancel()
	}()
	r.Run(ctx)

	if filePlug.callCount() == 0 {
		t.Fatal("file plugin was never called")
	}
	if svcPlug.callCount() == 0 {
		t.Fatal("service plugin was never called")
	}

	filePlug.mu.Lock()
	specs := filePlug.reconciled[0]
	filePlug.mu.Unlock()
	if len(specs) != 1 || specs[0].Kind != "file" {
		t.Fatalf("file plugin got wrong specs: %+v", specs)
	}

	svcPlug.mu.Lock()
	specs = svcPlug.reconciled[0]
	svcPlug.mu.Unlock()
	if len(specs) != 1 || specs[0].Kind != "service" {
		t.Fatalf("service plugin got wrong specs: %+v", specs)
	}
}

func TestReconcile_PluginError_SetsErrorStatus(t *testing.T) {
	r := New(1, testLogger())
	errPlug := &mockPlugin{
		name:    "err_plugin",
		handles: "file",
		actions: []Action{
			{Plugin: "err_plugin", Resource: "/etc/broken", ActionType: ActionUpdate, Error: "permission denied"},
		},
	}
	r.RegisterPlugin(errPlug)

	r.SetDesiredState(&model.DeviceManifest{
		Version: 1,
		Spec: model.ManifestSpec{
			Files: []model.FileResource{{Path: "/etc/broken"}},
		},
	})

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(100 * time.Millisecond)
		cancel()
	}()
	r.Run(ctx)

	if r.Status() != "error" {
		t.Fatalf("expected status=error, got %q", r.Status())
	}

	actions := r.LastActions()
	if len(actions) == 0 {
		t.Fatal("expected actions to be recorded")
	}
	if actions[0].Error != "permission denied" {
		t.Fatalf("expected error=permission denied, got %q", actions[0].Error)
	}
}

func TestReconcile_PluginError_OthersStillCalled(t *testing.T) {
	r := New(1, testLogger())
	errPlug := &mockPlugin{
		name:    "err_plugin",
		handles: "file",
		actions: []Action{{Plugin: "err_plugin", ActionType: ActionUpdate, Error: "fail"}},
	}
	okPlug := &mockPlugin{name: "ok_plugin", handles: "service"}
	r.RegisterPlugin(errPlug)
	r.RegisterPlugin(okPlug)

	r.SetDesiredState(&model.DeviceManifest{
		Version: 1,
		Spec: model.ManifestSpec{
			Files:    []model.FileResource{{Path: "/etc/broken"}},
			Services: []model.ServiceResource{{Name: "nginx", State: "running"}},
		},
	})

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(100 * time.Millisecond)
		cancel()
	}()
	r.Run(ctx)

	if errPlug.callCount() == 0 {
		t.Fatal("error plugin was never called")
	}
	if okPlug.callCount() == 0 {
		t.Fatal("ok plugin should still be called after error plugin")
	}
}

func TestReconcile_ContextCancellation_StopsLoop(t *testing.T) {
	r := New(1, testLogger())
	p := &mockPlugin{name: "fp", handles: "file"}
	r.RegisterPlugin(p)
	r.SetDesiredState(&model.DeviceManifest{
		Version: 1,
		Spec: model.ManifestSpec{
			Files: []model.FileResource{{Path: "/etc/test"}},
		},
	})

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		r.Run(ctx)
		close(done)
	}()

	// Let it run for a bit, then cancel.
	time.Sleep(50 * time.Millisecond)
	cancel()

	select {
	case <-done:
		// Good, Run() returned.
	case <-time.After(2 * time.Second):
		t.Fatal("Run() did not stop after context cancellation")
	}
}

func TestReconcile_ConcurrentSafety(t *testing.T) {
	r := New(1, testLogger())
	p := &mockPlugin{name: "fp", handles: "file"}
	r.RegisterPlugin(p)

	manifest := &model.DeviceManifest{
		Version: 1,
		Spec: model.ManifestSpec{
			Files: []model.FileResource{{Path: "/etc/test", Content: "x"}},
		},
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Run reconciler in background.
	go r.Run(ctx)

	// Concurrently set desired state and read status.
	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func(v int64) {
			defer wg.Done()
			m := *manifest
			m.Version = v
			r.SetDesiredState(&m)
			_ = r.Status()
			_ = r.GetDesiredState()
			_ = r.LastReconcile()
			_ = r.LastActions()
		}(int64(i))
	}
	wg.Wait()
	cancel()
}

func TestReconcile_LargeManifest(t *testing.T) {
	r := New(1, testLogger())
	p := &mockPlugin{name: "fp", handles: "file"}
	r.RegisterPlugin(p)

	var files []model.FileResource
	for i := 0; i < 50; i++ {
		files = append(files, model.FileResource{
			Path:    "/etc/gen/" + string(rune('a'+i%26)) + ".conf",
			Content: "data",
		})
	}

	r.SetDesiredState(&model.DeviceManifest{
		Version: 1,
		Spec:    model.ManifestSpec{Files: files},
	})

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(100 * time.Millisecond)
		cancel()
	}()
	r.Run(ctx)

	if p.callCount() == 0 {
		t.Fatal("plugin was never called for large manifest")
	}

	p.mu.Lock()
	specs := p.reconciled[0]
	p.mu.Unlock()
	if len(specs) != 50 {
		t.Fatalf("expected 50 specs dispatched, got %d", len(specs))
	}
}

func TestReconcile_LastReconcileUpdated(t *testing.T) {
	r := New(1, testLogger())
	before := time.Now()

	r.SetDesiredState(&model.DeviceManifest{
		Version: 1,
		Spec:    model.ManifestSpec{Files: []model.FileResource{{Path: "/a"}}},
	})

	p := &mockPlugin{name: "fp", handles: "file"}
	r.RegisterPlugin(p)

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(100 * time.Millisecond)
		cancel()
	}()
	r.Run(ctx)

	last := r.LastReconcile()
	if last.Before(before) {
		t.Fatalf("LastReconcile (%v) should be after test start (%v)", last, before)
	}
}

func TestReconcile_SetDesiredState_UpdatesManifest(t *testing.T) {
	r := New(30, testLogger())

	m1 := &model.DeviceManifest{Version: 1}
	r.SetDesiredState(m1)
	got := r.GetDesiredState()
	if got.Version != 1 {
		t.Fatalf("expected version 1, got %d", got.Version)
	}

	m2 := &model.DeviceManifest{Version: 5}
	r.SetDesiredState(m2)
	got = r.GetDesiredState()
	if got.Version != 5 {
		t.Fatalf("expected version 5, got %d", got.Version)
	}
}
