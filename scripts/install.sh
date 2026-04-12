#!/usr/bin/env bash
# One-shot EdgeGuardian install on minikube: builds images → helm install with
# runtime-detected IP so Keycloak OIDC redirects land on the right host.
#
#   ./scripts/install.sh              # default namespace "edgeguardian"
#   ./scripts/install.sh --clean      # uninstall + reinstall
#   ./scripts/install.sh --skip-build # skip image builds (reuse existing)

set -euo pipefail

NS="edgeguardian"
SKIP_BUILD=0
CLEAN=0

for arg in "$@"; do
    case "$arg" in
        --clean)      CLEAN=1 ;;
        --skip-build) SKIP_BUILD=1 ;;
        *)            NS="$arg" ;;
    esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHART="$ROOT/deployments/helm/edgeguardian"
CTRL="$ROOT/controller"
UI="$ROOT/ui"

for cmd in kubectl helm minikube docker; do
    command -v "$cmd" >/dev/null || { echo "'$cmd' not found in PATH"; exit 1; }
done

echo ">> Ensuring minikube is running"
if ! minikube status | grep -q "host: Running"; then
    minikube start --cpus=4 --memory=8g
fi

echo ">> Pointing docker at minikube's daemon"
eval "$(minikube -p minikube docker-env)"

if [ "$SKIP_BUILD" -ne 1 ]; then
    echo ">> Building controller image"
    (cd "$CTRL" && ./gradlew bootBuildImage)

    echo ">> Building UI image"
    (cd "$UI" && docker build -t edgeguardian/ui:latest .)
fi

IP="$(minikube ip | tr -d '[:space:]')"
UI_URL="http://${IP}:30080"
KC_URL="http://${IP}:30090/realms/edgeguardian"
echo ">> minikube IP: $IP"

if [ "$CLEAN" -eq 1 ]; then
    echo ">> Removing existing release + namespace"
    helm uninstall edgeguardian --namespace "$NS" >/dev/null 2>&1 || true
    kubectl delete namespace "$NS" --wait=true >/dev/null 2>&1 || true
fi

echo ">> Installing chart with UI=$UI_URL, Keycloak=$KC_URL"
helm upgrade --install edgeguardian "$CHART" \
    --namespace "$NS" --create-namespace \
    --set "ui.nextAuthUrl=$UI_URL" \
    --set "ui.keycloakIssuerUrl=$KC_URL" \
    --wait --timeout 15m

cat <<EOF

==============================================================
EdgeGuardian is up at:
  UI         $UI_URL
  Keycloak   http://${IP}:30090   (admin/admin)
  Controller http://${IP}:30443
  EMQX mTLS  ssl://${IP}:30883
  EMQX boot  tcp://${IP}:31883
==============================================================
EOF
