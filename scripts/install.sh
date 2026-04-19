#!/usr/bin/env bash
# Minikube dev bring-up: build images into minikube's docker, then helm install.
# Prod: just run `helm install` yourself - see deployments/helm/edgeguardian/README.md.

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

for cmd in kubectl helm minikube docker; do
    command -v "$cmd" >/dev/null || { echo "'$cmd' not found in PATH"; exit 1; }
done

if ! minikube status | grep -q "host: Running"; then
    minikube start --cpus=4 --memory=8g
fi
eval "$(minikube -p minikube docker-env)"

if [ "$SKIP_BUILD" -ne 1 ]; then
    (cd "$ROOT/controller" && ./gradlew bootBuildImage)
    (cd "$ROOT/ui" && docker build -t edgeguardian/ui:latest .)
fi

if [ "$CLEAN" -eq 1 ]; then
    helm uninstall edgeguardian -n "$NS" >/dev/null 2>&1 || true
    kubectl delete namespace "$NS" --wait=true >/dev/null 2>&1 || true
fi

IP="$(minikube ip | tr -d '[:space:]')"
helm upgrade --install edgeguardian "$CHART" \
    -n "$NS" --create-namespace \
    -f "$CHART/values-dev.yaml" \
    --set "ui.nextAuthUrl=http://${IP}:30080" \
    --set "ui.keycloakIssuerUrl=http://${IP}:30090/realms/edgeguardian" \
    --wait --timeout 15m

cat <<EOF

EdgeGuardian dev is up:
  UI         http://${IP}:30080
  Keycloak   http://${IP}:30090
  Controller http://${IP}:30443
EOF
