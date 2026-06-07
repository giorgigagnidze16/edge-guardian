#!/usr/bin/env bash
# One-time GCP bring-up: provision infra (Terraform) and install the cluster
# prerequisites (ingress-nginx pinned to the static IP, cert-manager, the
# Let's Encrypt ClusterIssuer). Run once after `gcloud auth login` and after
# filling deployments/terraform/terraform.tfvars.
#
# Usage:
#   LETSENCRYPT_EMAIL=you@example.com ./deployments/prod/bootstrap.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TF="$ROOT/deployments/terraform"
: "${LETSENCRYPT_EMAIL:?set LETSENCRYPT_EMAIL=you@example.com}"

for cmd in terraform gcloud kubectl helm; do
  command -v "$cmd" >/dev/null || { echo "'$cmd' not found in PATH"; exit 1; }
done

echo ">> terraform apply"
terraform -chdir="$TF" init
terraform -chdir="$TF" apply -auto-approve

INGRESS_IP="$(terraform -chdir="$TF" output -raw ingress_ip)"
CLUSTER="$(terraform -chdir="$TF" output -raw cluster_name)"
ZONE="$(terraform -chdir="$TF" output -raw zone)"

echo ">> cluster credentials"
gcloud container clusters get-credentials "$CLUSTER" --zone "$ZONE"

echo ">> ingress-nginx on static IP $INGRESS_IP"
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx >/dev/null 2>&1 || true
helm repo add jetstack https://charts.jetstack.io >/dev/null 2>&1 || true
helm repo update >/dev/null
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
  -n ingress-nginx --create-namespace \
  --set controller.service.loadBalancerIP="$INGRESS_IP" --wait

echo ">> cert-manager"
helm upgrade --install cert-manager jetstack/cert-manager \
  -n cert-manager --create-namespace --set crds.enabled=true --wait

echo ">> Let's Encrypt ClusterIssuer"
sed "s|REPLACE_WITH_YOUR_EMAIL|$LETSENCRYPT_EMAIL|" "$ROOT/deployments/prod/clusterissuer.yaml" \
  | kubectl apply -f -

echo
echo "Bootstrap complete. Record these for values-prod.yaml and GitHub secrets:"
terraform -chdir="$TF" output
