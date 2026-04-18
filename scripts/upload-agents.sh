#!/usr/bin/env bash
# Build agent binaries for the install matrix (using Docker — no local Go required)
# and upload them to MinIO via a throwaway in-cluster mc pod (no local mc required).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NS="${EG_NAMESPACE:-edgeguardian}"
BUCKET="${EG_BUCKET:-ota-artifacts}"
MINIO_USER="${EG_MINIO_USER:-admin}"
MINIO_PASS="${EG_MINIO_PASS:-adminadmin}"
GO_IMAGE="${EG_GO_IMAGE:-golang:1.24-alpine}"
MC_IMAGE="${EG_MC_IMAGE:-minio/mc:latest}"
MC_POD="${EG_MC_POD:-mc-upload-agents}"

# <goos> <goarch> <uploaded-filename>
PLATFORMS=(
  "linux amd64 edgeguardian-agent"
  "linux arm64 edgeguardian-agent"
  "windows amd64 edgeguardian-agent.exe"
)

mkdir -p "$ROOT/build"

# Git Bash / MSYS mangles absolute paths inside `docker run` args. Disable that translation
# for this block so `/src`, `/out`, `-w /src` stay literal.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

echo "=== Building agent binaries via Docker ==="
for entry in "${PLATFORMS[@]}"; do
  read -r os arch _ <<<"$entry"
  out="//out/edgeguardian-agent-${os}-${arch}"
  [[ "$os" == "windows" ]] && out="${out}.exe"
  echo "  $os/$arch"
  docker run --rm \
    -v "$ROOT/agent:/src:ro" \
    -v "$ROOT/build:/out" \
    -e CGO_ENABLED=0 -e GOOS="$os" -e GOARCH="$arch" \
    -w //src "$GO_IMAGE" \
    go build -ldflags="-s -w -X main.agentVersion=0.2.0" -o "$out" ./cmd/agent
done

echo "=== Launching throwaway mc pod in cluster ==="
kubectl -n "$NS" delete pod "$MC_POD" --ignore-not-found --now >/dev/null 2>&1 || true
kubectl -n "$NS" run "$MC_POD" --image="$MC_IMAGE" --restart=Never \
  --command -- sh -c 'sleep 900' >/dev/null
kubectl -n "$NS" wait --for=condition=Ready "pod/$MC_POD" --timeout=60s >/dev/null
trap 'kubectl -n "$NS" delete pod "$MC_POD" --ignore-not-found --now >/dev/null 2>&1 || true' EXIT

echo "=== Configuring mc alias + ensuring bucket exists ==="
kubectl -n "$NS" exec "$MC_POD" -- sh -c \
  "mc alias set eg http://minio:9000 '$MINIO_USER' '$MINIO_PASS' >/dev/null && \
   mc mb --ignore-existing eg/$BUCKET >/dev/null"

echo "=== Uploading binaries ==="
# Stream via stdin (minio/mc has no tar, so kubectl cp fails). MSYS_NO_PATHCONV=1
# above keeps /tmp literal inside `kubectl exec`.
cd "$ROOT/build"
for entry in "${PLATFORMS[@]}"; do
  read -r os arch uploaded_name <<<"$entry"
  src="edgeguardian-agent-${os}-${arch}"
  [[ "$os" == "windows" ]] && src="${src}.exe"
  [[ -f "$src" ]] || { echo "missing build/$src" >&2; exit 1; }

  remote="//tmp/$src"
  dst="eg/$BUCKET/public/agent/$os/$arch/$uploaded_name"
  echo "  build/$src -> $BUCKET/public/agent/$os/$arch/$uploaded_name"
  kubectl -n "$NS" exec -i "$MC_POD" -- sh -c "cat > $remote" < "$src"
  kubectl -n "$NS" exec "$MC_POD" -- mc cp "$remote" "$dst" >/dev/null
done
cd - >/dev/null

echo "=== Done. Verifying upload ==="
kubectl -n "$NS" exec "$MC_POD" -- mc ls --recursive "eg/$BUCKET/public/agent/"
