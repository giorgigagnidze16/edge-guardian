#!/usr/bin/env bash
# Build, sign, upload, and publish an agent version as the org's current channel.
# Used by .github/workflows/agent-release.yml. Required env:
#   VERSION             e.g. 0.5.0
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

PLATFORMS=("linux amd64" "linux arm64" "windows amd64" "darwin amd64" "darwin arm64")
REFERENCE_ID=""

for p in "${PLATFORMS[@]}"; do
  read -r os arch <<<"$p"
  bin="$OUT/edgeguardian-agent-${os}-${arch}"
  [ "$os" = "windows" ] && bin="${bin}.exe"

  echo ">> build ${os}/${arch}"
  (cd "$ROOT/agent" && CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" \
    go build -ldflags="-s -w -X main.agentVersion=${VERSION}" -o "$bin" ./cmd/agent)

  sig_hex="$(openssl pkeyutl -sign -inkey "$KEY" -rawin -in "$bin" | xxd -p -c 100000 | tr -d '\n')"

  echo ">> upload ${os}/${arch}"
  id="$(curl -fsS -X POST "$EG_CONTROLLER_URL/api/v1/ota/artifacts" \
    -H "X-API-Key: $EG_API_KEY" \
    -F "file=@${bin}" \
    -F "name=edgeguardian-agent" \
    -F "version=${VERSION}" \
    -F "architecture=${arch}" \
    -F "ed25519Sig=${sig_hex}" \
    | sed 's/.*"id":\([0-9]*\).*/\1/')"
  echo "   artifact id $id"
  [ "$os" = "linux" ] && [ "$arch" = "amd64" ] && REFERENCE_ID="$id"
done

: "${REFERENCE_ID:?no reference artifact uploaded}"
echo ">> publish channel current = artifact $REFERENCE_ID"
curl -fsS -X POST "$EG_CONTROLLER_URL/api/v1/ota/channel/current" \
  -H "X-API-Key: $EG_API_KEY" -H "Content-Type: application/json" \
  -d "{\"artifactId\":${REFERENCE_ID}}" >/dev/null

echo "Published agent ${VERSION}."
