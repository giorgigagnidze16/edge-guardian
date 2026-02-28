//go:build !linux

package filemanager

// checkOwnerPlatform on non-Linux platforms always returns true.
// Ownership management is only supported on Linux.
func checkOwnerPlatform(_, _ string) bool {
	return true
}
