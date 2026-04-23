#!/usr/bin/env bash
# Build the EdgeGuardian agent for various platforms.
# Usage:
#   ./scripts/build-agent.sh                  # build for current platform
#   ./scripts/build-agent.sh linux arm64       # cross-compile for RPi (64-bit)
#   ./scripts/build-agent.sh linux arm         # cross-compile for RPi (32-bit)
#   ./scripts/build-agent.sh android arm64     # cross-compile for Android

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
AGENT_DIR="$ROOT_DIR/agent"
OUTPUT_DIR="$ROOT_DIR/build"

TARGET_OS="${1:-$(go env GOOS)}"
TARGET_ARCH="${2:-$(go env GOARCH)}"
VERSION="${3:-0.2.0}"
UPX="${UPX:-$(command -v upx 2>/dev/null || true)}"

mkdir -p "$OUTPUT_DIR"

echo "=== Building EdgeGuardian Agent ==="
echo "  OS:      $TARGET_OS"
echo "  Arch:    $TARGET_ARCH"
echo "  Version: $VERSION"

# Build from the agent module directory
cd "$AGENT_DIR"

# Build agent
echo ""
echo "Building agent..."
CGO_ENABLED=0 GOOS="$TARGET_OS" GOARCH="$TARGET_ARCH" go build \
  -ldflags="-s -w -X main.agentVersion=$VERSION" \
  -o "$OUTPUT_DIR/edgeguardian-agent-${TARGET_OS}-${TARGET_ARCH}" \
  ./cmd/agent

# Build watchdog
echo "Building watchdog..."
CGO_ENABLED=0 GOOS="$TARGET_OS" GOARCH="$TARGET_ARCH" go build \
  -ldflags="-s -w" \
  -o "$OUTPUT_DIR/edgeguardian-watchdog-${TARGET_OS}-${TARGET_ARCH}" \
  ./cmd/watchdog

cd "$ROOT_DIR"

# Compress with UPX if available
if [ -n "$UPX" ]; then
  echo ""
  echo "Compressing with UPX..."
  for bin in "$OUTPUT_DIR"/edgeguardian-*-${TARGET_OS}-${TARGET_ARCH}; do
    echo "  $(basename "$bin")"
    "$UPX" --best --quiet "$bin"
  done
fi

# Report sizes
echo ""
echo "Build artifacts:"
ls -lh "$OUTPUT_DIR"/edgeguardian-*-${TARGET_OS}-${TARGET_ARCH}

# Verify agent is under 5MB
AGENT_BIN="$OUTPUT_DIR/edgeguardian-agent-${TARGET_OS}-${TARGET_ARCH}"
AGENT_SIZE=$(stat -f%z "$AGENT_BIN" 2>/dev/null || stat -c%s "$AGENT_BIN" 2>/dev/null || wc -c < "$AGENT_BIN")
MAX_SIZE=$((5 * 1024 * 1024))
if [ "$AGENT_SIZE" -gt "$MAX_SIZE" ]; then
  echo ""
  echo "WARNING: Agent binary exceeds 5MB target ($(( AGENT_SIZE / 1024 / 1024 ))MB)"
  exit 1
fi

echo ""
echo "=== Done ==="
