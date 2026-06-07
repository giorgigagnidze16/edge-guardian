#!/usr/bin/env bash
# Stop the cluster: scale the node pool to 0. Compute billing stops; the load
# balancer, static IP, TLS cert, and persistent disks remain so `cluster-up.sh`
# restores the exact same setup. Usage: ./scripts/cluster-down.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TF="$ROOT/deployments/terraform"
CLUSTER="$(terraform -chdir="$TF" output -raw cluster_name)"
ZONE="$(terraform -chdir="$TF" output -raw zone)"

echo ">> scaling $CLUSTER to 0 nodes"
gcloud container clusters resize "$CLUSTER" --node-pool primary --num-nodes 0 --zone "$ZONE" --quiet

echo "Down. You now pay only for disks + the load balancer (~a few \$/month)."
