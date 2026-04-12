# EMQX mTLS certs

The SSL listener on port 8883 expects three PEM files in this directory:

- `emqx-server.crt` / `emqx-server.key` — the broker's own TLS server cert. For local dev
  you can generate a self-signed pair:
  ```bash
  openssl req -x509 -newkey rsa:2048 -sha256 -days 365 -nodes \
    -keyout emqx-server.key -out emqx-server.crt \
    -subj "/CN=emqx.localhost"
  ```
- `ca-bundle.pem` — concatenated PEMs of every EdgeGuardian organization CA. EMQX uses
  this bundle to validate client (device) certs. For local dev with a single org:
  ```bash
  curl -s http://localhost:8443/api/v1/certificates/ca \
    -H "Authorization: Bearer $TOKEN" > ca-bundle.pem
  ```
  When a new organization is created, re-run this command (with that org's token) and
  append its CA to the bundle. EMQX picks up changes on its next CRL refresh tick
  (default 5 min — see `EMQX_CRL_CACHE__REFRESH_INTERVAL`).

**CRL enforcement.** Each issued leaf cert embeds a CRL Distribution Point (CDP)
extension pointing at `http://controller:8443/api/v1/pki/crl/{orgId}.crl`. On every TLS
handshake EMQX follows that URL, fetches the signed CRL, and rejects the client if its
serial is listed. Revocations therefore take effect on the next handshake attempt —
existing sessions must be force-disconnected separately (controller does this via the
EMQX admin API).
