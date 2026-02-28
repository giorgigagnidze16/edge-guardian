#!/usr/bin/env bash
# Generate protobuf code for Go agent and Java controller.
# Prerequisites:
#   - protoc (Protocol Buffers compiler)
#   - protoc-gen-go, protoc-gen-go-grpc (Go plugins)
#   - For Java: Gradle handles protobuf generation via protobuf-gradle-plugin

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROTO_DIR="$ROOT_DIR/proto"
GO_OUT_DIR="$ROOT_DIR/agent/internal/comms/proto"

echo "=== EdgeGuardian Proto Generation ==="

# --- Go ---
echo "Generating Go protobuf code..."
mkdir -p "$GO_OUT_DIR"

protoc \
  --proto_path="$PROTO_DIR" \
  --go_out="$GO_OUT_DIR" \
  --go_opt=paths=source_relative \
  --go-grpc_out="$GO_OUT_DIR" \
  --go-grpc_opt=paths=source_relative \
  "$PROTO_DIR"/*.proto

echo "Go code generated in: $GO_OUT_DIR"

# --- Java ---
# Java protobuf generation is handled by the protobuf-gradle-plugin
# in controller/build.gradle. Run: cd controller && ./gradlew generateProto
echo ""
echo "Java protobuf code is generated via Gradle."
echo "Run: cd controller && ./gradlew generateProto"

echo ""
echo "=== Done ==="
