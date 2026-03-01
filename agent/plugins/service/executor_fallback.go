//go:build !linux && !windows && !darwin && !android

package service

import (
	"context"
	"fmt"
	"runtime"
)

// UnsupportedExecutor returns errors for platforms without dedicated service
// management support. The agent will still start, but service reconciliation
// resources will be skipped with a clear error.
type UnsupportedExecutor struct{}

func newPlatformExecutor() ServiceExecutor {
	return &UnsupportedExecutor{}
}

func (u *UnsupportedExecutor) IsActive(_ context.Context, name string) (bool, error) {
	return false, fmt.Errorf("service management not supported on %s", runtime.GOOS)
}

func (u *UnsupportedExecutor) IsEnabled(_ context.Context, name string) (bool, error) {
	return false, fmt.Errorf("service management not supported on %s", runtime.GOOS)
}

func (u *UnsupportedExecutor) Start(_ context.Context, name string) error {
	return fmt.Errorf("service management not supported on %s", runtime.GOOS)
}

func (u *UnsupportedExecutor) Stop(_ context.Context, name string) error {
	return fmt.Errorf("service management not supported on %s", runtime.GOOS)
}

func (u *UnsupportedExecutor) Enable(_ context.Context, name string) error {
	return fmt.Errorf("service management not supported on %s", runtime.GOOS)
}

func (u *UnsupportedExecutor) Disable(_ context.Context, name string) error {
	return fmt.Errorf("service management not supported on %s", runtime.GOOS)
}
