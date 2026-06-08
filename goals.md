# EdgeGuardian — Shipping Goals

Tracking what's done vs. what's left to ship the thesis project.

**Legend:** `[x]` ~~struck = implemented & prod-ready~~ · `[ ]` open = remaining.

---

## Core platform

- [x] ~~Go agent: reconciliation loop, diff/apply, plugin model~~
- [x] ~~Offline-first persistence (BoltDB), cached desired-state replay~~
- [x] ~~Pure-MQTT 5 data plane (heartbeat, telemetry, commands, logs, certs) over EMQX~~
- [x] ~~Watchdog supervisor: crash backoff, binary swap (exit 42), auto-rollback~~
- [x] ~~Spring Boot controller: REST API, virtual threads, layered service architecture~~
- [x] ~~PostgreSQL 16 + TimescaleDB hypertable, Flyway migrations~~
- [x] ~~Multi-tenancy: org-scoped resources, `TenantPrincipal`, cross-tenant 404 isolation~~
- [x] ~~Keycloak OAuth2 + RBAC (VIEWER < OPERATOR < ADMIN < OWNER), API keys~~
- [x] ~~PKI / mTLS: per-org CA, CSR-in-enroll, two-phase connect, renewal loop, CRL + EMQX kickout~~
- [x] ~~Next.js dashboard (devices, certs, audit, integrations, settings, manifest editor)~~

## Agent platform support

- [x] ~~Linux / Windows / macOS / Android builds (build-tag separated)~~
- [x] ~~<5 MB binary (CGO disabled, gRPC dropped, UPX) — ADR-001/002~~
- [x] ~~Plugins: filemanager, service, certificate, gpio~~

## Agent binary distribution & self-update

- [x] ~~Single global agent binary served from MinIO (`public/agent/<os>/<arch>/`)~~
- [x] ~~Signed publish endpoint `POST /api/v1/agent/binaries` (OPERATOR+, mandatory Ed25519 verify) + `release.json` sidecar~~
- [x] ~~Public fetch endpoints: `GET /api/v1/agent/binary`, `GET /api/v1/agent/latest-version`~~
- [x] ~~Pull-based self-update: poll latest-version → download → verify sha256 + Ed25519 → exit 42 → watchdog swap~~
- [x] ~~Install-time `auto_update` prompt (Linux / macOS / Windows installers) gating the auto loop~~
- [x] ~~`edge-guardian --update` manual trigger via local control endpoint~~
- [x] ~~`agent-release.yml`: build + sign + publish every platform on push to main (version = git SHA)~~
- [x] ~~Removed the old push-based OTA subsystem (controller + agent + UI) in favor of one pull model~~
- [ ] Wire the release keypair: CI `OTA_SIGNING_KEY` ↔ controller `OTA_PUBLIC_KEY`, plus `EG_CONTROLLER_URL` / `EG_API_KEY` (OPERATOR) secrets
- [ ] End-to-end verification against a live stack (publish → install pulls latest → auto-update + `--update` swap)

## CI/CD & production infrastructure

- [x] ~~`ci.yml`: controller tests + agent tests + UI build on PR/main~~
- [x] ~~`deploy.yml`: build/push controller + UI images to Artifact Registry, helm upgrade via WIF on merge to main~~
- [x] ~~Terraform: GKE, Artifact Registry, static IPs, Workload Identity Federation, KMS, deployer SA~~
- [x] ~~Helm chart for the full stack (controller, UI, EMQX, Keycloak, Postgres, MinIO, Loki, Grafana, ingress, PKI bootstrap)~~
- [x] ~~`values-prod.yaml` + `values-prod-secrets.yaml.example`~~
- [ ] Execute first production deploy on GKE (apply Terraform, bootstrap first org)
- [ ] DNS + TLS live (ingress-nginx + cert-manager + Let's Encrypt ClusterIssuer) validated end-to-end
- [ ] NodePort / broker URL mapping verified for real edge devices

## Observability & health

- [x] ~~Agent self-metrics (CPU / memory / disk / uptime) in heartbeat~~
- [x] ~~Log forwarding to Loki; Grafana for dashboards~~
- [ ] Manifest-driven health probes (`HealthCheckSpec` http/tcp/exec) — defined in the model but **not enforced** by a reconciler plugin
- [ ] Grafana dashboards curated for fleet/thesis demo

## Testing & thesis deliverables

- [x] ~~Agent unit + integration tests (config, reconciler, health, MQTT, storage, plugins, systemd)~~
- [x] ~~Controller integration tests via Testcontainers (lifecycle, tenancy, PKI/CRL, enroll-with-CSR)~~
- [ ] Fleet simulation / scale benchmarks (e.g. N-device load against the controller)
- [ ] End-to-end smoke test of the full enroll → reconcile → self-update path in CI
- [ ] Thesis writeup (architecture, ADRs, evaluation, results)
