#!/usr/bin/env bash
# Start the cluster: scale the node pool back up. Data, load balancer, IP, and
# TLS cert all persisted while it was off, so it comes back at the same URL.
# Usage: ./scripts/cluster-up.sh [node_count]   (default 1)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TF="$ROOT/deployments/terraform"
NODES="${1:-1}"
CLUSTER="$(terraform -chdir="$TF" output -raw cluster_name)"
ZONE="$(terraform -chdir="$TF" output -raw zone)"
BASE="$(terraform -chdir="$TF" output -raw base_domain)"

echo ">> scaling $CLUSTER to $NODES node(s)"
gcloud container clusters resize "$CLUSTER" --node-pool primary --num-nodes "$NODES" --zone "$ZONE" --quiet
gcloud container clusters get-credentials "$CLUSTER" --zone "$ZONE"

echo ">> waiting for the controller"
kubectl -n edgeguardian rollout status deploy/controller --timeout=300s || true

echo
echo "Up. Dashboard: https://ui.${BASE}"
