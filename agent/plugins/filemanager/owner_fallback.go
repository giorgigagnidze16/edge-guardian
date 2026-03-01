//go:build !linux && !windows && !darwin

package filemanager

// checkOwnerPlatform on unsupported platforms always returns true.
// Ownership management requires platform-specific syscalls.
func checkOwnerPlatform(_, _ string) bool {
	return true
}
