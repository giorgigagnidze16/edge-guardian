// Package storage provides persistent local storage for the EdgeGuardian agent
// using BoltDB. It stores desired state, offline message queues, and agent metadata.
// This enables offline-first operation: the agent can survive restarts and network
// outages without losing state.
package storage

import (
	"encoding/json"
	"fmt"
	"time"

	bolt "go.etcd.io/bbolt"
)

// Bucket names used by the agent.
var (
	bucketDesiredState = []byte("desired_state")
	bucketOfflineQueue = []byte("offline_queue")
	bucketAgentMeta    = []byte("agent_meta")
)

// Store wraps BoltDB and provides typed access to agent data.
type Store struct {
	db *bolt.DB
}

// Open creates or opens a BoltDB database at the given path.
func Open(path string) (*Store, error) {
	db, err := bolt.Open(path, 0600, &bolt.Options{
		Timeout: 5 * time.Second,
	})
	if err != nil {
		return nil, fmt.Errorf("open bolt db at %s: %w", path, err)
	}

	// Create buckets on first open.
	err = db.Update(func(tx *bolt.Tx) error {
		for _, name := range [][]byte{bucketDesiredState, bucketOfflineQueue, bucketAgentMeta} {
			if _, err := tx.CreateBucketIfNotExists(name); err != nil {
				return fmt.Errorf("create bucket %s: %w", string(name), err)
			}
		}
		return nil
	})
	if err != nil {
		db.Close()
		return nil, fmt.Errorf("initialize buckets: %w", err)
	}

	return &Store{db: db}, nil
}

// Close closes the underlying BoltDB database.
func (s *Store) Close() error {
	if s.db != nil {
		return s.db.Close()
	}
	return nil
}

// SaveDesiredState stores the desired state JSON under a key.
// Typically key is the device ID or "current".
func (s *Store) SaveDesiredState(key string, data interface{}) error {
	value, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("marshal desired state: %w", err)
	}
	return s.db.Update(func(tx *bolt.Tx) error {
		return tx.Bucket(bucketDesiredState).Put([]byte(key), value)
	})
}

// LoadDesiredState reads the desired state for the given key into dest.
// Returns false if the key does not exist (dest is not modified).
func (s *Store) LoadDesiredState(key string, dest interface{}) (bool, error) {
	var raw []byte
	err := s.db.View(func(tx *bolt.Tx) error {
		raw = tx.Bucket(bucketDesiredState).Get([]byte(key))
		return nil
	})
	if err != nil {
		return false, fmt.Errorf("load desired state: %w", err)
	}
	if raw == nil {
		return false, nil
	}
	if err := json.Unmarshal(raw, dest); err != nil {
		return false, fmt.Errorf("unmarshal desired state: %w", err)
	}
	return true, nil
}

// QueuedMessage wraps a payload with metadata for the offline queue.
type QueuedMessage struct {
	Topic     string    `json:"topic"`
	Payload   []byte    `json:"payload"`
	QueuedAt  time.Time `json:"queuedAt"`
}

// EnqueueMessage appends a message to the offline queue.
// Messages are keyed by a monotonically increasing sequence number so
// they are dequeued in FIFO order.
func (s *Store) EnqueueMessage(msg *QueuedMessage) error {
	value, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("marshal queued message: %w", err)
	}
	return s.db.Update(func(tx *bolt.Tx) error {
		b := tx.Bucket(bucketOfflineQueue)
		seq, err := b.NextSequence()
		if err != nil {
			return fmt.Errorf("next sequence: %w", err)
		}
		key := fmt.Sprintf("%020d", seq)
		return b.Put([]byte(key), value)
	})
}

// DequeueMessages removes and returns up to limit messages from the offline queue.
// Messages are returned in FIFO order (oldest first).
func (s *Store) DequeueMessages(limit int) ([]*QueuedMessage, error) {
	var messages []*QueuedMessage
	var keysToDelete [][]byte

	err := s.db.Update(func(tx *bolt.Tx) error {
		b := tx.Bucket(bucketOfflineQueue)
		c := b.Cursor()

		count := 0
		for k, v := c.First(); k != nil && count < limit; k, v = c.Next() {
			var msg QueuedMessage
			if err := json.Unmarshal(v, &msg); err != nil {
				// Skip corrupted entries but still delete them.
				keysToDelete = append(keysToDelete, append([]byte(nil), k...))
				continue
			}
			messages = append(messages, &msg)
			keysToDelete = append(keysToDelete, append([]byte(nil), k...))
			count++
		}

		for _, key := range keysToDelete {
			if err := b.Delete(key); err != nil {
				return fmt.Errorf("delete dequeued message: %w", err)
			}
		}

		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("dequeue messages: %w", err)
	}

	return messages, nil
}

// QueueDepth returns the number of messages in the offline queue.
func (s *Store) QueueDepth() (int, error) {
	var count int
	err := s.db.View(func(tx *bolt.Tx) error {
		b := tx.Bucket(bucketOfflineQueue)
		count = b.Stats().KeyN
		return nil
	})
	return count, err
}

// SaveMeta stores an arbitrary key-value pair in agent metadata.
func (s *Store) SaveMeta(key, value string) error {
	return s.db.Update(func(tx *bolt.Tx) error {
		return tx.Bucket(bucketAgentMeta).Put([]byte(key), []byte(value))
	})
}

// GetMeta retrieves a metadata value by key. Returns "" if not found.
func (s *Store) GetMeta(key string) (string, error) {
	var value string
	err := s.db.View(func(tx *bolt.Tx) error {
		raw := tx.Bucket(bucketAgentMeta).Get([]byte(key))
		if raw != nil {
			value = string(raw)
		}
		return nil
	})
	return value, err
}
