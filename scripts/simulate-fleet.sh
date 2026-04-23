#!/usr/bin/env bash
# Simulate a fleet of EdgeGuardian devices by sending registration + heartbeat requests
# Usage: ./scripts/simulate-fleet.sh [num_devices] [heartbeat_rounds]

set -euo pipefail

CONTROLLER="http://localhost:8443"
NUM_DEVICES=${1:-10}
HEARTBEAT_ROUNDS=${2:-5}

HOSTNAMES=("rpi-gateway-01" "rpi-sensor-02" "rpi-camera-03" "esp32-temp-04" "nuc-edge-05"
           "rpi-relay-06" "jetson-ai-07" "rpi-monitor-08" "beagle-ctrl-09" "rpi-hub-10"
           "rpi-node-11" "esp32-valve-12" "nuc-proc-13" "rpi-display-14" "jetson-vision-15")
ARCHES=("arm64" "arm64" "arm64" "arm" "amd64" "arm64" "arm64" "arm64" "arm64" "arm64"
        "arm64" "arm" "amd64" "arm64" "arm64")
LOCATIONS=("warehouse-a" "warehouse-a" "warehouse-b" "greenhouse" "server-room"
           "warehouse-b" "loading-dock" "office" "factory-floor" "server-room"
           "warehouse-a" "greenhouse" "server-room" "office" "loading-dock")
ROLES=("gateway" "sensor" "camera" "sensor" "compute" "relay" "ai-inference" "monitor" "controller" "gateway"
       "sensor" "actuator" "compute" "display" "ai-inference")

echo "=== EdgeGuardian Fleet Simulator ==="
echo "Registering $NUM_DEVICES devices with $HEARTBEAT_ROUNDS heartbeat rounds..."
echo ""

# Register devices
for i in $(seq 0 $((NUM_DEVICES - 1))); do
  idx=$((i % ${#HOSTNAMES[@]}))
  device_id="${HOSTNAMES[$idx]}"
  hostname="${HOSTNAMES[$idx]}"
  arch="${ARCHES[$idx]}"
  location="${LOCATIONS[$idx]}"
  role="${ROLES[$idx]}"

  echo -n "Registering $device_id... "
  response=$(curl -s -w "\n%{http_code}" -X POST "$CONTROLLER/api/v1/agent/register" \
    -H "Content-Type: application/json" \
    -d "{
      \"deviceId\": \"$device_id\",
      \"hostname\": \"$hostname\",
      \"architecture\": \"$arch\",
      \"os\": \"linux\",
      \"agentVersion\": \"0.2.0\",
      \"labels\": {\"role\": \"$role\", \"location\": \"$location\", \"env\": \"production\"}
    }" 2>/dev/null)
  code=$(echo "$response" | tail -1)
  echo "HTTP $code"
done

echo ""
echo "Sending $HEARTBEAT_ROUNDS rounds of heartbeats..."

# Send heartbeats with varied metrics
for round in $(seq 1 $HEARTBEAT_ROUNDS); do
  echo ""
  echo "--- Heartbeat round $round/$HEARTBEAT_ROUNDS ---"
  for i in $(seq 0 $((NUM_DEVICES - 1))); do
    idx=$((i % ${#HOSTNAMES[@]}))
    device_id="${HOSTNAMES[$idx]}"

    # Generate somewhat realistic random metrics
    cpu=$(( (RANDOM % 60) + 5 ))
    mem_total=4294967296  # 4GB
    mem_pct=$(( (RANDOM % 50) + 20 ))
    mem_used=$(( mem_total * mem_pct / 100 ))
    disk_total=34359738368  # 32GB
    disk_pct=$(( (RANDOM % 40) + 15 ))
    disk_used=$(( disk_total * disk_pct / 100 ))
    uptime=$(( (RANDOM % 86400) + 3600 ))

    # Make some devices degraded
    state="online"
    if [ $i -eq 3 ] || [ $i -eq 7 ]; then
      cpu=$(( (RANDOM % 15) + 85 ))  # High CPU
      state="online"
    fi
    if [ $i -eq 5 ]; then
      state="online"
      mem_pct=96
      mem_used=$(( mem_total * mem_pct / 100 ))
    fi

    echo -n "  $device_id: cpu=${cpu}% mem=${mem_pct}%... "
    response=$(curl -s -w "\n%{http_code}" -X POST "$CONTROLLER/api/v1/agent/heartbeat" \
      -H "Content-Type: application/json" \
      -d "{
        \"deviceId\": \"$device_id\",
        \"agentVersion\": \"0.2.0\",
        \"status\": {
          \"state\": \"$state\",
          \"cpuUsagePercent\": $cpu.$(( RANDOM % 10 )),
          \"memoryUsedBytes\": $mem_used,
          \"memoryTotalBytes\": $mem_total,
          \"diskUsedBytes\": $disk_used,
          \"diskTotalBytes\": $disk_total,
          \"uptimeSeconds\": $uptime,
          \"lastReconcile\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",
          \"reconcileStatus\": \"converged\"
        },
        \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
      }" 2>/dev/null)
    code=$(echo "$response" | tail -1)
    echo "HTTP $code"
  done

  if [ $round -lt $HEARTBEAT_ROUNDS ]; then
    echo "  Waiting 5s before next round..."
    sleep 5
  fi
done

echo ""
echo "=== Fleet simulation complete ==="
echo "Check http://localhost:3001 to see the dashboard with live data"

# Verify
echo ""
echo "Registered devices:"
curl -s "$CONTROLLER/api/v1/devices/count" 2>/dev/null
echo " devices total"