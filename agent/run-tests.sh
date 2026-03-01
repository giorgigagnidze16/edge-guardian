#!/bin/bash
# Integration test runner for the EdgeGuardian Go agent.
# Can run standalone (starts Mosquitto manually) or under systemd (Mosquitto
# already running as a service). Works both ways.
set -e

export MQTT_BROKER_URL="tcp://127.0.0.1:1883"

# Start Mosquitto if it is not already running (e.g. when invoked outside systemd).
if ! pgrep -x mosquitto > /dev/null 2>&1; then
    echo "Starting Mosquitto broker..."
    mosquitto -d -c /etc/mosquitto/conf.d/test.conf
    sleep 1
fi

PASS=0
FAIL=0
ERRORS=""

run_test() {
    local name="$1"
    local binary="$2"
    echo "=========================================="
    echo "  Running: $name"
    echo "=========================================="
    if "$binary" -test.v -test.count=1 -test.timeout=120s; then
        PASS=$((PASS + 1))
    else
        FAIL=$((FAIL + 1))
        ERRORS="$ERRORS\n  FAIL: $name"
    fi
    echo ""
}

echo ""
echo "EdgeGuardian Agent - Integration Tests"
echo "Running inside $(cat /etc/os-release | grep PRETTY_NAME | cut -d= -f2)"
echo "Kernel: $(uname -r)"
echo "Mosquitto: $(mosquitto -h 2>&1 | head -1 || echo 'installed')"
echo "Systemd: $(systemctl is-system-running 2>/dev/null || echo 'not ready')"
echo ""

run_test "config"       /tests/config.test
run_test "model"        /tests/model.test
run_test "storage"      /tests/storage.test
run_test "health"       /tests/health.test
run_test "reconciler"   /tests/reconciler.test
run_test "filemanager"  /tests/filemanager.test
run_test "comms (HTTP + MQTT)" /tests/comms.test
run_test "service (systemd)"   /tests/service.test

echo "=========================================="
echo "  Results: $PASS passed, $FAIL failed"
if [ $FAIL -gt 0 ]; then
    echo -e "  Failures:$ERRORS"
fi
echo "=========================================="

# Signal completion. If running under systemd, this lets the container
# be stopped after tests finish.
if [ $FAIL -gt 0 ]; then
    exit 1
fi
exit 0
