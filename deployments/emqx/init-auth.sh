#!/bin/sh
# Provisions MQTT users in EMQX built-in database via REST API.
# Runs as a sidecar after EMQX is healthy.
set -e

EMQX_API="http://emqx:18083/api/v5"
EMQX_USER="admin"
EMQX_PASS="admin-secret"

# Login to get a JWT token.
TOKEN=$(curl -sf -X POST "$EMQX_API/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$EMQX_USER\",\"password\":\"$EMQX_PASS\"}" | sed 's/.*"token":"\([^"]*\)".*/\1/')

if [ -z "$TOKEN" ]; then
  echo "ERROR: failed to login to EMQX dashboard API"
  exit 1
fi

create_user() {
  user_id=$1
  password=$2
  status=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$EMQX_API/authentication/password_based:built_in_database/users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"user_id\":\"$user_id\",\"password\":\"$password\"}")

  case $status in
    201) echo "  created: $user_id" ;;
    409) echo "  exists:  $user_id" ;;
    *)   echo "  FAILED:  $user_id (HTTP $status)" ;;
  esac
}

echo "Provisioning EMQX MQTT users..."
create_user "controller"  "controller-secret"
create_user "test-device" "test-device-secret"
echo "Done."
