#!/usr/bin/env bash
# Build, sign, and publish every agent platform as the global latest version.
# Used by .github/workflows/agent-release.yml. Required env:
#   VERSION             agent version, e.g. a git short SHA
#   EG_CONTROLLER_URL   e.g. https://controller.<base>
#   EG_API_KEY          org API key with OPERATOR+ rights
#   OTA_SIGNING_KEY     Ed25519 private key in PEM (signs each binary)
set -euo pipefail

: "${VERSION:?}" "${EG_CONTROLLER_URL:?}" "${EG_API_KEY:?}" "${OTA_SIGNING_KEY:?}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/release"
KEY="$(mktemp)"
printf '%s' "$OTA_SIGNING_KEY" > "$KEY"
mkdir -p "$OUT"

PLATFORMS=("linux amd64" "linux arm64" "linux arm" "windows amd64" "darwin amd64" "darwin arm64")

for p in "${PLATFORMS[@]}"; do
  read -r os arch <<<"$p"
  bin="$OUT/edgeguardian-agent-${os}-${arch}"
  [ "$os" = "windows" ] && bin="${bin}.exe"

  echo ">> build ${os}/${arch}"
  (cd "$ROOT/agent" && CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" \
    go build -ldflags="-s -w -X main.agentVersion=${VERSION}" -o "$bin" ./cmd/agent)

  sig_hex="$(openssl pkeyutl -sign -inkey "$KEY" -rawin -in "$bin" | xxd -p -c 100000 | tr -d '\n')"

  echo ">> publish ${os}/${arch} version ${VERSION}"
  curl -fsS -X POST "$EG_CONTROLLER_URL/api/v1/agent/binaries?os=${os}&arch=${arch}&version=${VERSION}" \
    -H "X-API-Key: $EG_API_KEY" \
    -F "ed25519Sig=${sig_hex}" \
    -F "file=@${bin}" >/dev/null
done

echo "Published agent ${VERSION} for ${#PLATFORMS[@]} platforms."
