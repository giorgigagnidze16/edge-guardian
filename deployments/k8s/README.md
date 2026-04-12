# EdgeGuardian — Kubernetes Deployment

Production deployment via Kustomize. One-shot bring-up, cert-manager handles TLS, no
docker-compose chicken-and-egg dance.

## Prerequisites

1. **Kubernetes cluster.** Any 1.27+ cluster works. For local dev on Windows, Docker
   Desktop's Kubernetes or `kind` both work fine.
2. **cert-manager** (for TLS automation):
   ```bash
   kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.16.2/cert-manager.yaml
   kubectl wait --for=condition=Available --timeout=120s -n cert-manager deployment/cert-manager-webhook
   ```
3. **trust-manager** (for CA bundle distribution):
   ```bash
   helm repo add jetstack https://charts.jetstack.io
   helm upgrade --install trust-manager jetstack/trust-manager \
       --namespace cert-manager \
       --wait
   ```
4. **kubectl 1.27+** (bundled with Kustomize v5).

## Build the controller image

```bash
cd controller
./gradlew bootBuildImage
```

Paketo produces `edgeguardian/controller:0.2.0` and `edgeguardian/controller:latest` on
your local Docker daemon. For kind, also load it into the cluster:

```bash
kind load docker-image edgeguardian/controller:latest
```

Docker Desktop Kubernetes shares the daemon — no load step needed.

## Deploy

```bash
kubectl apply -k deployments/k8s/overlays/dev
```

That's it. Kustomize resolves the base + dev overlay and applies ~35 resources in the
`edgeguardian` namespace.

Watch it come up:

```bash
kubectl -n edgeguardian get pods -w
```

Expected sequence (takes 2-3 min on first boot):

1. `postgres-0` and `minio-0` become Ready first.
2. `keycloak-*` waits for postgres, then becomes Ready.
3. cert-manager creates the broker CA + EMQX server cert (look for
   `kubectl -n edgeguardian get certificate`).
4. `controller-*` becomes Ready once Postgres is up and migrations run.
5. `emqx-0` init container polls the controller's `/api/v1/pki/ca-bundle` until an
   org CA exists (see "First org" below), then EMQX starts.
6. `emqx-init-users-*` Job runs, provisions `controller` + `bootstrap` users, exits 0.

## First org (one-time)

EMQX's init container waits for at least one organization CA to exist. First time:

```bash
# Port-forward Keycloak + controller
kubectl -n edgeguardian port-forward svc/keycloak 9090:9090 &
kubectl -n edgeguardian port-forward svc/controller 8443:8443 &
```

Open http://localhost:9090, log in as admin/admin, then open
http://localhost:3000 (ui dev server, or deploy it here too — not included in this overlay)
and log in via Keycloak. First login auto-creates a personal org; the controller
materializes its CA eagerly. Verify:

```bash
curl http://localhost:8443/api/v1/pki/ca-bundle
# should return PEM + X-CA-Count: 1
```

EMQX's init container's next 2-second poll will succeed and EMQX will boot.

## Accessing services from outside the cluster

Dev overlay exposes these NodePorts:

| Port | Service          | Purpose                              |
|-----:|------------------|--------------------------------------|
| 30443 | controller       | REST API (CRL + enrollment webhooks) |
| 30883 | emqx (mqtts)     | mTLS device traffic                  |
| 31883 | emqx (mqtt)      | bootstrap device traffic             |

On Docker Desktop the NodeIP is `localhost`. On kind use
`docker inspect kind-control-plane --format='{{.NetworkSettings.IPAddress}}'`.

Agent config for a device connecting via NodePort:

```yaml
mqtt:
  broker_url: tcp://<node-ip>:31883
  username: bootstrap
  password: bootstrap-secret
  mtls_broker_url: ssl://<node-ip>:30883
  ca_cert_path: /var/lib/edgeguardian/identity/ca.crt
  identity_cert_path: /var/lib/edgeguardian/identity/identity.crt
  identity_key_path: /var/lib/edgeguardian/identity/identity.key
  tls_server_name: emqx           # cert-manager issues with SAN dnsNames: emqx, localhost
  insecure_skip_verify: false     # FINALLY — broker CA ships with enroll response
```

## Updating the controller

After code changes:

```bash
cd controller
./gradlew bootBuildImage
kind load docker-image edgeguardian/controller:latest   # kind only
kubectl -n edgeguardian rollout restart deployment/controller
```

## Production overlay (not yet in this repo)

Create `overlays/prod/` that patches:

- All Secrets → SealedSecrets or ExternalSecrets
- Controller Deployment → real ingress with a real hostname + TLS
- EMQX external service → LoadBalancer with an Ingress-level annotation forwarding TLS
- Storage class → use your cloud provider's default
- Replica counts → 2+ for controller, 3+ for emqx (clustered)
- `PKI_CRL_URL` env → your public controller hostname
- EMQX broker cert DNS → your public FQDN instead of `emqx`/`localhost`

## Troubleshooting

**EMQX stuck in Init.**
```bash
kubectl -n edgeguardian logs emqx-0 -c fetch-ca-bundle
```
If it says "controller HTTP 200 or empty body" repeating, no org CA exists yet. Do the
"First org" step.

**cert-manager Certificate not Ready.**
```bash
kubectl -n edgeguardian describe certificate emqx-server
kubectl -n edgeguardian describe certificaterequest
```
Usually means the Issuer isn't ready yet — cert-manager cascades: `selfsigned-root` →
`edgeguardian-broker-ca` CA cert → `edgeguardian-broker-ca` Issuer → `emqx-server` cert.
Takes ~15s on first boot.

**trust-manager Bundle empty.**
```bash
kubectl -n edgeguardian get configmap broker-ca -o yaml
```
If `broker-ca.pem` is empty, the source Secret (`edgeguardian-broker-ca-secret`) probably
hasn't been created yet by cert-manager. Wait for the Certificate to go Ready.

**Agent gets "unknown authority" on mTLS connect.**
The controller isn't appending the broker CA to its enrollment response. Check:
```bash
kubectl -n edgeguardian exec deployment/controller -- cat /pki/broker-ca/broker-ca.pem
```
If empty, trust-manager hasn't published yet. Otherwise re-run enrollment — the issue
only affects devices enrolled before the broker CA was available.
