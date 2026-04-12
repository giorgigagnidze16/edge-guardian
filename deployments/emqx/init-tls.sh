#!/bin/sh
# EMQX mTLS bootstrap — runs before the broker starts.
#
# Responsibilities:
#   1. Generate the broker's own TLS server cert/key (self-signed for dev; replace with
#      a proper chain in prod by dropping emqx-server.{crt,key} into /certs before start).
#   2. Fetch the concatenated CA bundle from the controller so EMQX can validate every
#      device's client certificate at TLS handshake.
#
# Idempotent: won't overwrite existing server cert/key, and will refresh the CA bundle
# every time it runs (it's cheap and the bundle changes as organizations are added).

set -eu

CERT_DIR="/certs"
SERVER_CRT="$CERT_DIR/emqx-server.crt"
SERVER_KEY="$CERT_DIR/emqx-server.key"
CA_BUNDLE="$CERT_DIR/ca-bundle.pem"

CONTROLLER_URL="${CONTROLLER_URL:-http://controller:8443}"
EMQX_HOSTNAME="${EMQX_HOSTNAME:-emqx}"

mkdir -p "$CERT_DIR"

# --- Server cert ------------------------------------------------------------------------
if [ -s "$SERVER_CRT" ] && [ -s "$SERVER_KEY" ]; then
    echo "server cert already present at $SERVER_CRT — keeping it"
else
    echo "generating self-signed server cert for CN=$EMQX_HOSTNAME"
    openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
        -keyout "$SERVER_KEY" \
        -out "$SERVER_CRT" \
        -subj "/CN=$EMQX_HOSTNAME/O=EdgeGuardian" \
        -addext "subjectAltName=DNS:$EMQX_HOSTNAME,DNS:localhost,IP:127.0.0.1"
    chmod 644 "$SERVER_CRT"
    chmod 600 "$SERVER_KEY"
fi

# --- CA bundle --------------------------------------------------------------------------
echo "fetching CA bundle from $CONTROLLER_URL/api/v1/pki/ca-bundle"

# Wait for the controller to be reachable and for at least one org CA to exist.
# Hard cap at 120 attempts * 2s = 4 minutes so a broken deploy doesn't hang forever.
i=1
while [ $i -le 120 ]; do
    status=$(curl -s -o "$CA_BUNDLE.tmp" -w "%{http_code}" "$CONTROLLER_URL/api/v1/pki/ca-bundle" || echo "000")
    if [ "$status" = "200" ] && [ -s "$CA_BUNDLE.tmp" ]; then
        mv "$CA_BUNDLE.tmp" "$CA_BUNDLE"
        chmod 644 "$CA_BUNDLE"
        echo "CA bundle written to $CA_BUNDLE ($(wc -l < "$CA_BUNDLE") lines)"
        break
    fi
    echo "  attempt $i: controller returned HTTP $status, bundle empty — retrying in 2s"
    sleep 2
    i=$((i + 1))
done

if [ ! -s "$CA_BUNDLE" ]; then
    echo "ERROR: could not fetch a non-empty CA bundle after 4 minutes"
    exit 1
fi

echo "EMQX mTLS bootstrap complete"
