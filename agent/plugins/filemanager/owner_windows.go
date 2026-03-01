//go:build windows

package filemanager

// checkOwnerPlatform on Windows always returns true.
// POSIX-style user:group ownership is not applicable on Windows.
func checkOwnerPlatform(_, _ string) bool {
	return true
}
