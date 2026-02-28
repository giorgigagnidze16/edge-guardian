package storage

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func tempDB(t *testing.T) *Store {
	t.Helper()
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test.db")
	store, err := Open(dbPath)
	if err != nil {
		t.Fatalf("failed to open store: %v", err)
	}
	t.Cleanup(func() { store.Close() })
	return store
}

func TestOpenAndClose(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test.db")

	store, err := Open(dbPath)
	if err != nil {
		t.Fatalf("Open failed: %v", err)
	}
	if err := store.Close(); err != nil {
		t.Fatalf("Close failed: %v", err)
	}

	// Verify file was created.
	if _, err := os.Stat(dbPath); os.IsNotExist(err) {
		t.Fatal("database file was not created")
	}
}

func TestDesiredState_SaveAndLoad(t *testing.T) {
	store := tempDB(t)

	type testManifest struct {
		Name    string `json:"name"`
		Version int    `json:"version"`
	}

	original := testManifest{Name: "sensor-01", Version: 3}
	if err := store.SaveDesiredState("current", original); err != nil {
		t.Fatalf("SaveDesiredState: %v", err)
	}

	var loaded testManifest
	found, err := store.LoadDesiredState("current", &loaded)
	if err != nil {
		t.Fatalf("LoadDesiredState: %v", err)
	}
	if !found {
		t.Fatal("expected to find desired state, got not found")
	}
	if loaded.Name != original.Name || loaded.Version != original.Version {
		t.Fatalf("loaded state mismatch: got %+v, want %+v", loaded, original)
	}
}

func TestDesiredState_NotFound(t *testing.T) {
	store := tempDB(t)

	var dest struct{}
	found, err := store.LoadDesiredState("nonexistent", &dest)
	if err != nil {
		t.Fatalf("LoadDesiredState: %v", err)
	}
	if found {
		t.Fatal("expected not found, got found")
	}
}

func TestDesiredState_Overwrite(t *testing.T) {
	store := tempDB(t)

	type data struct {
		Value string `json:"value"`
	}

	if err := store.SaveDesiredState("key", data{Value: "first"}); err != nil {
		t.Fatalf("first save: %v", err)
	}
	if err := store.SaveDesiredState("key", data{Value: "second"}); err != nil {
		t.Fatalf("second save: %v", err)
	}

	var loaded data
	found, err := store.LoadDesiredState("key", &loaded)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if !found {
		t.Fatal("expected found")
	}
	if loaded.Value != "second" {
		t.Fatalf("expected 'second', got %q", loaded.Value)
	}
}

func TestOfflineQueue_EnqueueDequeue(t *testing.T) {
	store := tempDB(t)

	// Enqueue 3 messages.
	for i := 0; i < 3; i++ {
		msg := &QueuedMessage{
			Topic:    "test/topic",
			Payload:  []byte(`{"index":` + string(rune('0'+i)) + `}`),
			QueuedAt: time.Now(),
		}
		if err := store.EnqueueMessage(msg); err != nil {
			t.Fatalf("enqueue %d: %v", i, err)
		}
	}

	// Check depth.
	depth, err := store.QueueDepth()
	if err != nil {
		t.Fatalf("QueueDepth: %v", err)
	}
	if depth != 3 {
		t.Fatalf("expected depth 3, got %d", depth)
	}

	// Dequeue 2.
	msgs, err := store.DequeueMessages(2)
	if err != nil {
		t.Fatalf("dequeue: %v", err)
	}
	if len(msgs) != 2 {
		t.Fatalf("expected 2 messages, got %d", len(msgs))
	}
	if msgs[0].Topic != "test/topic" {
		t.Fatalf("expected topic 'test/topic', got %q", msgs[0].Topic)
	}

	// Remaining depth should be 1.
	depth, err = store.QueueDepth()
	if err != nil {
		t.Fatalf("QueueDepth after dequeue: %v", err)
	}
	if depth != 1 {
		t.Fatalf("expected depth 1, got %d", depth)
	}

	// Dequeue remaining.
	msgs, err = store.DequeueMessages(10)
	if err != nil {
		t.Fatalf("dequeue remaining: %v", err)
	}
	if len(msgs) != 1 {
		t.Fatalf("expected 1 message, got %d", len(msgs))
	}
}

func TestOfflineQueue_EmptyDequeue(t *testing.T) {
	store := tempDB(t)

	msgs, err := store.DequeueMessages(10)
	if err != nil {
		t.Fatalf("dequeue empty: %v", err)
	}
	if len(msgs) != 0 {
		t.Fatalf("expected 0 messages from empty queue, got %d", len(msgs))
	}
}

func TestOfflineQueue_FIFOOrder(t *testing.T) {
	store := tempDB(t)

	topics := []string{"first", "second", "third"}
	for _, topic := range topics {
		if err := store.EnqueueMessage(&QueuedMessage{
			Topic:    topic,
			Payload:  []byte("data"),
			QueuedAt: time.Now(),
		}); err != nil {
			t.Fatalf("enqueue %s: %v", topic, err)
		}
	}

	msgs, err := store.DequeueMessages(3)
	if err != nil {
		t.Fatalf("dequeue: %v", err)
	}

	for i, want := range topics {
		if msgs[i].Topic != want {
			t.Errorf("message %d: got topic %q, want %q", i, msgs[i].Topic, want)
		}
	}
}

func TestMeta_SaveAndGet(t *testing.T) {
	store := tempDB(t)

	if err := store.SaveMeta("agent_version", "0.2.0"); err != nil {
		t.Fatalf("SaveMeta: %v", err)
	}

	val, err := store.GetMeta("agent_version")
	if err != nil {
		t.Fatalf("GetMeta: %v", err)
	}
	if val != "0.2.0" {
		t.Fatalf("expected '0.2.0', got %q", val)
	}
}

func TestMeta_NotFound(t *testing.T) {
	store := tempDB(t)

	val, err := store.GetMeta("nonexistent")
	if err != nil {
		t.Fatalf("GetMeta: %v", err)
	}
	if val != "" {
		t.Fatalf("expected empty string for missing key, got %q", val)
	}
}

func TestMeta_Overwrite(t *testing.T) {
	store := tempDB(t)

	if err := store.SaveMeta("key", "value1"); err != nil {
		t.Fatalf("first save: %v", err)
	}
	if err := store.SaveMeta("key", "value2"); err != nil {
		t.Fatalf("second save: %v", err)
	}

	val, err := store.GetMeta("key")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if val != "value2" {
		t.Fatalf("expected 'value2', got %q", val)
	}
}
