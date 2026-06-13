#!/usr/bin/env bash

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

for img in \
    timescale/timescaledb:2.17.2-pg16 \
    quay.io/keycloak/keycloak:26.0 \
    minio/minio:RELEASE.2024-12-18T13-15-44Z \
    grafana/loki:3.3.0 \
    grafana/grafana:11.4.0 \
    emqx/emqx:5.8 \
    axllent/mailpit:latest \
    alpine/k8s:1.31.1; do
    docker pull -q "$img" >/dev/null 2>&1 &
done

if [ "$SKIP_BUILD" -ne 1 ]; then
    cl="$(mktemp)"; ul="$(mktemp)"
    docker build -t edgeguardian/controller:latest "$ROOT/controller" >"$cl" 2>&1 & cpid=$!
    docker build -t edgeguardian/ui:latest         "$ROOT/ui"         >"$ul" 2>&1 & upid=$!
    echo "building controller + ui images in parallel..."
    wait "$cpid" || { echo "controller build failed:"; cat "$cl"; exit 1; }
    wait "$upid" || { echo "ui build failed:";         cat "$ul"; exit 1; }
    rm -f "$cl" "$ul"
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
