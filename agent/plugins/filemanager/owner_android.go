//go:build android

package filemanager

import (
	"fmt"
	"os"
	"os/user"
	"strings"
	"syscall"
)

// checkOwnerPlatform verifies file ownership on Android.
// Android uses the same syscall.Stat_t as Linux.
func checkOwnerPlatform(path, desiredOwner string) bool {
	parts := strings.SplitN(desiredOwner, ":", 2)
	if len(parts) != 2 {
		return false
	}

	info, err := os.Stat(path)
	if err != nil {
		return false
	}

	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return false
	}

	// Look up desired user.
	u, err := user.Lookup(parts[0])
	if err != nil {
		return false
	}
	desiredUID := fmt.Sprintf("%d", stat.Uid)
	if u.Uid != desiredUID {
		return false
	}

	// Look up desired group.
	g, err := user.LookupGroup(parts[1])
	if err != nil {
		return false
	}
	desiredGID := fmt.Sprintf("%d", stat.Gid)
	return g.Gid == desiredGID
}
