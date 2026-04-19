# EdgeGuardian Helm chart

Per-environment values files. All credentials come from the values files - dev defaults live in `values-dev.yaml`; prod passwords live in a gitignored `values-prod-secrets.yaml` that you create locally.

## Dev (minikube)

```bash
eval $(minikube docker-env)
cd controller && ./gradlew bootBuildImage
cd ../ui && docker build -t edgeguardian/ui:latest .

helm upgrade --install edgeguardian deployments/helm/edgeguardian \
  -n edgeguardian --create-namespace \
  -f deployments/helm/edgeguardian/values-dev.yaml
```

Or `./scripts/install.sh` does the above end-to-end.

## Prod

### 1. Cluster prerequisites (once per cluster)

```bash
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo add jetstack https://charts.jetstack.io
helm repo update

helm install ingress-nginx ingress-nginx/ingress-nginx \
  -n ingress-nginx --create-namespace --wait

helm install cert-manager jetstack/cert-manager \
  -n cert-manager --create-namespace \
  --set crds.enabled=true --wait
```

ClusterIssuer for Let's Encrypt (replace email):

```bash
kubectl apply -f - <<'EOF'
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: you@example.com
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
      - http01:
          ingress:
            class: nginx
EOF
```

### 2. Fill in prod passwords

Copy the example to `values-prod-secrets.yaml` (gitignored) and fill in real values:

```bash
cp deployments/helm/edgeguardian/values-prod-secrets.yaml.example \
   deployments/helm/edgeguardian/values-prod-secrets.yaml
$EDITOR deployments/helm/edgeguardian/values-prod-secrets.yaml
# Generate strong keys for nextAuthSecret and caEncryptionKey:
#   openssl rand -base64 32
```

### 3. Install

All HTTP URLs are derived from `ingress.baseDomain`. Set it and everything else
(UI, Keycloak issuer, CRL endpoint, controller agent-installer URL) is computed
via `_helpers.tpl`. MQTT cannot traverse the HTTP ingress - a dedicated
LoadBalancer is provisioned for EMQX (`emqx.external.type: LoadBalancer` in
`values-prod.yaml`); grab its IP/hostname once it's assigned and set the
`controller.agentInstaller.brokerUrl` / `mtlsBrokerUrl` so agents receive the
correct endpoints.

```bash
LB_IP=$(kubectl -n ingress-nginx get svc ingress-nginx-controller \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

helm upgrade --install edgeguardian deployments/helm/edgeguardian \
  -n edgeguardian --create-namespace \
  -f deployments/helm/edgeguardian/values-prod.yaml \
  -f deployments/helm/edgeguardian/values-prod-secrets.yaml \
  --set "ingress.baseDomain=${LB_IP}.sslip.io" \
  --wait --timeout 20m

# After the EMQX LoadBalancer Service has an external IP:
MQTT_IP=$(kubectl -n edgeguardian get svc emqx-external \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

helm upgrade edgeguardian deployments/helm/edgeguardian \
  -n edgeguardian \
  -f deployments/helm/edgeguardian/values-prod.yaml \
  -f deployments/helm/edgeguardian/values-prod-secrets.yaml \
  --set "ingress.baseDomain=${LB_IP}.sslip.io" \
  --set "controller.agentInstaller.brokerUrl=tcp://${MQTT_IP}:1883" \
  --set "controller.agentInstaller.mtlsBrokerUrl=ssl://${MQTT_IP}:8883" \
  --wait
```

UI lives at `https://ui.<LB_IP>.sslip.io`. The `keycloak-init-client` post-install
hook rotates the OIDC client secret from `values-prod-secrets.yaml` (the realm
JSON's dev placeholder is overwritten on every install).

## First-org bootstrap

Log in through the UI; a personal org is auto-created on first Keycloak login, which writes the initial CA and unblocks EMQX's init container.

## Password rotation

Edit `values-prod-secrets.yaml`, re-run the `helm upgrade` command, then `kubectl rollout restart` the affected workloads.

## Uninstall

```bash
helm uninstall edgeguardian -n edgeguardian
kubectl -n edgeguardian delete pvc --all
kubectl delete namespace edgeguardian
```
