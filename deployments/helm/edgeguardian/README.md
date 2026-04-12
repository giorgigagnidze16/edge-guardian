# EdgeGuardian Helm chart

One command, full stack. No external chart dependencies — the chart ships a
pre-install Job that generates the broker CA + MQTT server cert via openssl,
so there's nothing to install out of band beyond a running cluster and the
controller image.

## Prerequisites

- Kubernetes 1.27+ (tested on minikube, kind, Docker Desktop K8s)
- Helm 3.13+
- The controller image built and available to the cluster

## Build the controller image

On minikube:
```bash
eval $(minikube docker-env)
cd controller && ./gradlew bootBuildImage
```

On kind:
```bash
cd controller && ./gradlew bootBuildImage
kind load docker-image edgeguardian/controller:latest
```

On Docker Desktop: just `./gradlew bootBuildImage` — shared daemon.

## Install

```bash
cd deployments/helm/edgeguardian
# (no `helm dependency update` needed — the chart has no subchart dependencies)
helm install edgeguardian . \
  --namespace edgeguardian \
  --create-namespace \
  --wait --timeout 10m
```

`--wait` blocks until every Deployment/StatefulSet reports Ready. Typical first-boot
takes 2–3 minutes on a warm cluster.

## First-org (one-time)

EMQX's init container polls the controller's `/api/v1/pki/ca-bundle` endpoint until
at least one organization CA exists. Create the first org via Keycloak login:

```bash
kubectl -n edgeguardian port-forward svc/keycloak 9090:9090 &
kubectl -n edgeguardian port-forward svc/controller 8443:8443 &
# Open http://localhost:9090 → log in admin/admin
# Then open your UI (or hit /api/v1/me directly) → first login auto-creates a personal org
```

The EMQX init container picks up the bundle on its next 2-second poll and the stack
finishes booting.

## Reaching the stack from outside the cluster

Default overlay exposes NodePorts:

| Port | Service                  | Use case                              |
|-----:|--------------------------|---------------------------------------|
| 30443 | controller REST API      | CRL / CA bundle / admin API           |
| 30883 | EMQX mTLS listener       | Post-enrollment device traffic        |
| 31883 | EMQX bootstrap listener  | Pre-enrollment device bootstrap       |

On minikube: `minikube ip` → `192.168.49.2`, or `minikube service emqx-external -n edgeguardian --url`.

To disable NodePort (prod — use Ingress separately):
```bash
helm upgrade edgeguardian . --set externalAccess.mode=none
```

## Values you probably want to override in prod

```yaml
# custom-values.yaml
image:
  tag: 0.2.1           # pin a specific controller version

externalAccess:
  mode: none           # no NodePort; wire Ingress separately

controller:
  replicas: 2
  caEncryptionKey: "<base64-32-bytes>"   # openssl rand -base64 32
  crlUrl: https://edgeguardian.example.com/api/v1/pki/crl
  keycloakIssuerUri: https://auth.example.com/realms/edgeguardian

postgres:
  credentials:
    password: "<from-vault>"

keycloak:
  admin:
    password: "<from-vault>"

emqx:
  dashboardPassword: "<from-vault>"
  mqttUsers:
    controllerPassword: "<from-vault>"
    bootstrapPassword:  "<from-vault>"
```

```bash
helm upgrade edgeguardian . -f custom-values.yaml
```

For real secret management, point at an ExternalSecrets Operator or SealedSecrets
installation instead of baking values into YAML.

## Upgrade

```bash
# after rebuilding the controller image with a new tag
helm upgrade edgeguardian . --set image.tag=0.2.1 --wait
```

## Uninstall

```bash
helm uninstall edgeguardian --namespace edgeguardian
kubectl delete namespace edgeguardian
```

PVCs are NOT removed automatically by `helm uninstall` (intentional — Helm 3 leaves
data behind). Delete them explicitly:
```bash
kubectl -n edgeguardian delete pvc --all
```

Or if you want a full cluster reset: `kubectl delete namespace edgeguardian` removes
everything in the namespace including PVCs. cert-manager and trust-manager pods live
in the same namespace (subchart default) and will also be removed.

## Troubleshooting

**`helm install` fails with "no matches for kind Certificate":**
Chart dependencies probably weren't pulled. Run `helm dependency update` and retry.

**cert-manager webhook not Ready after 10 min:**
Your cluster may need more resources. Check `kubectl -n edgeguardian describe pod -l app.kubernetes.io/name=cert-manager-webhook` for events.

**EMQX stuck in Init:**
```bash
kubectl -n edgeguardian logs emqx-0 -c fetch-ca-bundle
```
"controller HTTP 200 or empty body" repeating → create the first org (see above).

**Agent `x509: unknown authority` on mTLS connect:**
You enrolled before trust-manager populated the `broker-ca` ConfigMap. Delete the
agent's `data/identity/` directory and re-enroll with a fresh token.
