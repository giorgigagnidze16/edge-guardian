#!/usr/bin/env bash
# Generate prod secrets once, write them to values-prod-secrets.yaml (for a
# manual first install) and push them to GitHub Actions secrets (for the deploy
# workflow). Infra secrets come from Terraform outputs. Run after bootstrap.sh,
# from the repo root, with `gh` authenticated to the target repo.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TF="$ROOT/deployments/terraform"
SECRETS_FILE="$ROOT/deployments/helm/edgeguardian/values-prod-secrets.yaml"

for cmd in terraform gh openssl; do
  command -v "$cmd" >/dev/null || { echo "'$cmd' not found in PATH"; exit 1; }
done

rand() { openssl rand -base64 32 | tr -d '\n'; }

PG_PASSWORD="$(rand)"
KEYCLOAK_ADMIN_PASSWORD="$(rand)"
MINIO_PASSWORD="$(rand)"
GRAFANA_PASSWORD="$(rand)"
EMQX_DASHBOARD_PASSWORD="$(rand)"
EMQX_CONTROLLER_PASSWORD="$(rand)"
EMQX_BOOTSTRAP_PASSWORD="$(rand)"
NEXTAUTH_SECRET="$(rand)"
KEYCLOAK_CLIENT_SECRET="$(rand)"
CA_ENCRYPTION_KEY="$(openssl rand -base64 32 | tr -d '\n')"
OTA_SIGNING_KEY="$(openssl genpkey -algorithm ed25519 -outform DER 2>/dev/null | openssl pkey -inform DER -text -noout 2>/dev/null | tr -d '\n' || echo "")"

echo ">> writing $SECRETS_FILE"
cat > "$SECRETS_FILE" <<EOF
postgres:
  credentials:
    password: "$PG_PASSWORD"
keycloak:
  admin:
    password: "$KEYCLOAK_ADMIN_PASSWORD"
minio:
  credentials:
    password: "$MINIO_PASSWORD"
grafana:
  admin:
    password: "$GRAFANA_PASSWORD"
emqx:
  dashboardPassword: "$EMQX_DASHBOARD_PASSWORD"
  mqttUsers:
    controllerPassword: "$EMQX_CONTROLLER_PASSWORD"
    bootstrapPassword: "$EMQX_BOOTSTRAP_PASSWORD"
ui:
  nextAuthSecret: "$NEXTAUTH_SECRET"
  keycloakClientSecret: "$KEYCLOAK_CLIENT_SECRET"
controller:
  caEncryptionKey: "$CA_ENCRYPTION_KEY"
mail:
  password: "${EG_MAIL_PASSWORD:-REPLACE_ME}"
EOF

echo ">> setting GitHub Actions secrets"
set_secret() { printf '%s' "$2" | gh secret set "$1"; }

set_secret GCP_PROJECT  "$(terraform -chdir="$TF" output -raw registry | cut -d/ -f2)"
set_secret GCP_REGION   "$(terraform -chdir="$TF" output -raw region)"
set_secret GKE_CLUSTER  "$(terraform -chdir="$TF" output -raw cluster_name)"
set_secret GKE_ZONE     "$(terraform -chdir="$TF" output -raw zone)"
set_secret WIF_PROVIDER "$(terraform -chdir="$TF" output -raw wif_provider)"
set_secret DEPLOY_SA    "$(terraform -chdir="$TF" output -raw deployer_sa_email)"

set_secret PG_PASSWORD               "$PG_PASSWORD"
set_secret KEYCLOAK_ADMIN_PASSWORD   "$KEYCLOAK_ADMIN_PASSWORD"
set_secret MINIO_PASSWORD            "$MINIO_PASSWORD"
set_secret GRAFANA_PASSWORD          "$GRAFANA_PASSWORD"
set_secret EMQX_DASHBOARD_PASSWORD   "$EMQX_DASHBOARD_PASSWORD"
set_secret EMQX_CONTROLLER_PASSWORD  "$EMQX_CONTROLLER_PASSWORD"
set_secret EMQX_BOOTSTRAP_PASSWORD   "$EMQX_BOOTSTRAP_PASSWORD"
set_secret NEXTAUTH_SECRET           "$NEXTAUTH_SECRET"
set_secret KEYCLOAK_CLIENT_SECRET    "$KEYCLOAK_CLIENT_SECRET"
set_secret CA_ENCRYPTION_KEY         "$CA_ENCRYPTION_KEY"

if [ -n "${EG_MAIL_PASSWORD:-}" ]; then
  set_secret MAIL_PASSWORD "$EG_MAIL_PASSWORD"
fi

echo "Done. Secrets written locally and pushed to GitHub."
echo "For Gmail in prod: run with EG_MAIL_PASSWORD=<app-password> and set"
echo "mail.from + mail.username (your Gmail address) in values-prod.yaml."
