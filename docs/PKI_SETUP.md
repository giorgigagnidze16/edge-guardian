# EdgeGuardian PKI / mTLS Setup

This document describes how the production mTLS pipeline works end-to-end. It's the
operator's reference for "what exactly is doing the work when a device connects?"

## Architecture

```
┌─────────────────┐         ┌──────────────────┐         ┌────────────────┐
│   Go Agent      │         │    EMQX 5.8      │         │  Controller    │
│                 │         │                  │         │  (Spring Boot) │
│  cert + key     │◀━mTLS━━▶│  SSL listener    │         │                │
│  on disk        │         │  :8883           │         │  CA + CRL      │
│                 │         │  + CRL check     │◀━HTTP──▶│  publishing    │
│                 │         │                  │         │                │
│  bootstrap      │───1883─▶│  TCP listener    │◀───tcp──│  controller    │
│  (first boot)   │         │  :1883 password  │         │  (password)    │
└─────────────────┘         └──────────────────┘         └────────────────┘
```

Two MQTT listeners, enforced by EMQX ACL:

| Listener  | Port | Auth                    | Who uses it                        | Topics allowed                                                   |
|-----------|------|-------------------------|------------------------------------|------------------------------------------------------------------|
| Enrollment| 1883 | username/password       | Un-enrolled devices (`bootstrap`) and the controller itself | Enrollment + cert-request only (for `bootstrap`); full for `controller` |
| Device    | 8883 | mTLS (cert CN = clientid) | Enrolled devices               | `edgeguardian/device/${clientid}/...` only — scoped by cert CN   |

## First-boot flow

1. Agent reads `mqtt.broker_url` (tcp) + `mqtt.username=bootstrap` + password.
2. Agent generates an ECDSA P-256 keypair locally. Private key never leaves the device.
3. Agent connects to the enrollment listener, publishes `enroll/request` carrying the
   enrollment token **and** a PEM-encoded CSR.
4. Controller's `EnrollmentListener` validates the token, registers the device, signs
   the CSR via `CertificateService` (type=MANIFEST, auto-approved for first-time devices),
   and publishes `enroll/response` containing `deviceToken`, `caCertPem`, `identityCertPem`,
   and `identityCertSerial`.
5. Agent persists the three files atomically to `{data_dir}/identity/`:
   - `identity.key` (0600)
   - `identity.crt` (0644)
   - `ca.crt` (0644)
6. Agent closes the bootstrap connection and reconnects to `mqtt.mtls_broker_url`
   presenting the fresh identity cert. EMQX validates it against the CA bundle and
   CDP-fetches the CRL to check revocation status.
7. Normal operation begins: heartbeats, telemetry, commands, all over mTLS.

## Subsequent boots

The agent checks `{data_dir}/identity/identity.crt` on startup. If present and parseable,
it skips the bootstrap flow entirely and connects directly to the mTLS listener. The
enrollment token is only needed once.

## Renewal

A background loop checks the cert's `NotAfter` every hour. When within 14 days of expiry,
the agent generates a new keypair + CSR, publishes `cert/request` with `type=RENEWAL` and
`currentSerial` set, and swaps the on-disk material atomically when the new cert arrives.
The old cert is marked `revoked=RENEWED` by the controller; a fresh CRL is published.

## Revocation

Any call to `CertificateService.revoke`, `revokeAllActiveForDevice`, or the compromise /
device-deletion paths:

1. Sets `revoked=true` in `certificates`.
2. Calls `CrlService.rebuild(orgId)` — produces a freshly-signed CRL blob in
   `certificate_revocation_lists`.
3. Calls `EmqxAdminClient.kickout(deviceId)` — force-disconnects any live session.

Next TLS handshake: EMQX refreshes the CRL (default every 5 min), sees the serial, rejects
the client at handshake time. Live sessions were already evicted in step 3.

## Infrastructure setup

Bring everything up with:

```bash
docker compose -f deployments/docker-compose.yml up -d
```

The `emqx-tls-init` container runs **before** EMQX starts:

1. Generates `emqx-server.{crt,key}` if not present (self-signed RSA-2048 for dev —
   replace with a proper chain for internet-facing deployments by dropping files into
   the `emqx_certs` volume before first boot).
2. Polls `http://host.docker.internal:8443/api/v1/pki/ca-bundle` until the controller
   responds with a non-empty bundle (up to 4 minutes).
3. Writes the bundle to `ca-bundle.pem` on the shared volume.

EMQX then boots with:
- `ssl_options.verify = verify_peer`
- `ssl_options.fail_if_no_peer_cert = true`
- `ssl_options.enable_crl_check = true`
- `crl_cache.refresh_interval = 300s`

The CDP extension embedded in every leaf cert points to
`http://controller:8443/api/v1/pki/crl/{orgId}.crl`, so EMQX knows where to fetch the
revocation list on each handshake.

## Operator actions

### Adding a new organization
No action needed. The controller's `PkiBootstrapRunner` materializes the CA on
next startup; the CA bundle endpoint picks it up automatically on the next EMQX
`emqx-tls-init` run (or EMQX restart).

### Replacing the EMQX server cert with a real one
Drop your `emqx-server.crt` and `emqx-server.key` into the `emqx_certs` volume before
first boot, or replace and restart EMQX. The init script is idempotent — it won't
overwrite files that already exist.

### Rotating an organization's CA
Not supported through this pipeline. Rotating a CA invalidates every device cert it
signed — requires all devices to re-enroll. Treat as a major-incident operation.

### Verifying a running agent's cert
SSH to the device and:
```bash
openssl x509 -in /var/lib/edgeguardian/identity/identity.crt -noout -text
```
Fingerprint is logged at agent startup — cross-check against controller's
`GET /api/v1/certificates` (filter by device).
