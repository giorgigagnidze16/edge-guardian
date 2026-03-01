//go:build windows

package service

import (
	"context"
	"fmt"
	"time"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

// RealWindowsSCM manages Windows services via the Service Control Manager API.
// Requires Administrator or SYSTEM privileges to open and modify services.
type RealWindowsSCM struct{}

func newPlatformExecutor() ServiceExecutor {
	return &RealWindowsSCM{}
}

func (w *RealWindowsSCM) openService(name string) (*mgr.Mgr, *mgr.Service, error) {
	m, err := mgr.Connect()
	if err != nil {
		return nil, nil, fmt.Errorf("connect to SCM (requires administrator privileges): %w", err)
	}
	s, err := m.OpenService(name)
	if err != nil {
		m.Disconnect()
		return nil, nil, fmt.Errorf("open service %q (verify service exists and agent has permissions): %w", name, err)
	}
	return m, s, nil
}

func (w *RealWindowsSCM) IsActive(_ context.Context, name string) (bool, error) {
	m, s, err := w.openService(name)
	if err != nil {
		return false, err
	}
	defer s.Close()
	defer m.Disconnect()

	status, err := s.Query()
	if err != nil {
		return false, fmt.Errorf("query service %q: %w", name, err)
	}
	return status.State == svc.Running, nil
}

func (w *RealWindowsSCM) IsEnabled(_ context.Context, name string) (bool, error) {
	m, s, err := w.openService(name)
	if err != nil {
		return false, err
	}
	defer s.Close()
	defer m.Disconnect()

	cfg, err := s.Config()
	if err != nil {
		return false, fmt.Errorf("query config for %q: %w", name, err)
	}
	return cfg.StartType == mgr.StartAutomatic || cfg.StartType == mgr.StartManual, nil
}

func (w *RealWindowsSCM) Start(_ context.Context, name string) error {
	m, s, err := w.openService(name)
	if err != nil {
		return err
	}
	defer s.Close()
	defer m.Disconnect()

	if err := s.Start(); err != nil {
		return fmt.Errorf("start service %q: %w", name, err)
	}
	return nil
}

func (w *RealWindowsSCM) Stop(ctx context.Context, name string) error {
	m, s, err := w.openService(name)
	if err != nil {
		return err
	}
	defer s.Close()
	defer m.Disconnect()

	_, err = s.Control(svc.Stop)
	if err != nil {
		return fmt.Errorf("stop service %q: %w", name, err)
	}

	// Wait for the service to stop, respecting context cancellation.
	timeout := time.After(10 * time.Second)
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return fmt.Errorf("context cancelled while waiting for %q to stop: %w", name, ctx.Err())
		case <-timeout:
			return fmt.Errorf("timeout waiting for service %q to stop (10s)", name)
		case <-ticker.C:
			status, err := s.Query()
			if err != nil {
				return fmt.Errorf("query service %q after stop: %w", name, err)
			}
			if status.State == svc.Stopped {
				return nil
			}
		}
	}
}

func (w *RealWindowsSCM) Enable(_ context.Context, name string) error {
	m, s, err := w.openService(name)
	if err != nil {
		return err
	}
	defer s.Close()
	defer m.Disconnect()

	cfg, err := s.Config()
	if err != nil {
		return fmt.Errorf("query config for %q: %w", name, err)
	}
	cfg.StartType = mgr.StartAutomatic
	if err := s.UpdateConfig(cfg); err != nil {
		return fmt.Errorf("enable service %q (requires administrator): %w", name, err)
	}
	return nil
}

func (w *RealWindowsSCM) Disable(_ context.Context, name string) error {
	m, s, err := w.openService(name)
	if err != nil {
		return err
	}
	defer s.Close()
	defer m.Disconnect()

	cfg, err := s.Config()
	if err != nil {
		return fmt.Errorf("query config for %q: %w", name, err)
	}
	cfg.StartType = windows.SERVICE_DISABLED
	if err := s.UpdateConfig(cfg); err != nil {
		return fmt.Errorf("disable service %q (requires administrator): %w", name, err)
	}
	return nil
}
