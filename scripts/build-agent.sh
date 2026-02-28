#!/usr/bin/env bash
# Build the EdgeGuardian agent for various platforms.
# Usage:
#   ./scripts/build-agent.sh                  # build for current platform
#   ./scripts/build-agent.sh linux arm64       # cross-compile for RPi (64-bit)
#   ./scripts/build-agent.sh linux arm         # cross-compile for RPi (32-bit)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
AGENT_DIR="$ROOT_DIR/agent"
OUTPUT_DIR="$ROOT_DIR/build"

TARGET_OS="${1:-$(go env GOOS)}"
TARGET_ARCH="${2:-$(go env GOARCH)}"
VERSION="${3:-0.1.0}"

mkdir -p "$OUTPUT_DIR"

echo "=== Building EdgeGuardian Agent ==="
echo "  OS:      $TARGET_OS"
echo "  Arch:    $TARGET_ARCH"
echo "  Version: $VERSION"

# Build agent
echo ""
echo "Building agent..."
CGO_ENABLED=0 GOOS="$TARGET_OS" GOARCH="$TARGET_ARCH" go build \
  -ldflags="-s -w -X main.agentVersion=$VERSION" \
  -o "$OUTPUT_DIR/edgeguardian-agent-${TARGET_OS}-${TARGET_ARCH}" \
  "$AGENT_DIR/cmd/agent"

# Build watchdog
echo "Building watchdog..."
CGO_ENABLED=0 GOOS="$TARGET_OS" GOARCH="$TARGET_ARCH" go build \
  -ldflags="-s -w" \
  -o "$OUTPUT_DIR/edgeguardian-watchdog-${TARGET_OS}-${TARGET_ARCH}" \
  "$AGENT_DIR/cmd/watchdog"

# Report sizes
echo ""
echo "Build artifacts:"
ls -lh "$OUTPUT_DIR"/edgeguardian-*-${TARGET_OS}-${TARGET_ARCH}

echo ""
echo "=== Done ==="
