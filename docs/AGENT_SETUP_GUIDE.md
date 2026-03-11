# IoTPilot Agent Setup Guide

Run the agent locally, register it with the controller, and view logs.

## Prerequisites

- **Go 1.24+** installed
- **Docker** running (for infrastructure services)
- **Controller** built and ready (`cd controller && ./gradlew build`)

## 1. Start Infrastructure

```bash
docker compose -f deployments/docker-compose.yml up -d
```

This starts:

| Service    | Port  | Credentials                        |
|------------|-------|------------------------------------|
| PostgreSQL | 5432  | `edgeguardian:edgeguardian-dev`    |
| Keycloak   | 9090  | `admin:admin`                      |
| EMQX       | 1883  | anonymous                          |
| MinIO      | 9000  | `edgeguardian:edgeguardian-dev`    |
| Loki       | 3100  | —                                  |
| Grafana    | 3002  | `admin:admin`                      |

## 2. Start the Controller

```bash
cd controller
./gradlew bootRun
```

Runs on `http://localhost:8443`.

## 3. Get an Enrollment Token

**Option A — Dashboard UI:**

1. `cd ui && npm run dev` → open `http://localhost:3000`
2. Sign in via Keycloak (`admin:admin`)
3. Go to **Settings → Tokens → Create Token**
4. Copy the `egt_xxxxx` value

**Option B — API:**

```bash
# Replace YOUR_JWT with a valid access token from Keycloak
curl -X POST http://localhost:8443/api/v1/organizations/1/enrollment-tokens \
  -H "Authorization: Bearer YOUR_JWT" \
  -H "Content-Type: application/json" \
  -d '{"description":"local-dev","maxUses":100}'
```

## 4. Build the Agent

From the repo root:

```bash
# Auto-detects your current OS/arch
./scripts/build-agent.sh

# Or target a specific platform
./scripts/build-agent.sh linux arm64      # Raspberry Pi 64-bit
./scripts/build-agent.sh linux arm        # Raspberry Pi 32-bit
./scripts/build-agent.sh windows amd64    # Windows
./scripts/build-agent.sh android arm64    # Android
```

Output binaries land in `build/`:
- `edgeguardian-agent-{os}-{arch}` (or `.exe` on Windows)
- `edgeguardian-watchdog-{os}-{arch}`

---

## 5. Configure & Run — Per OS

### Linux

**Default paths:**
- Config: `/etc/edgeguardian/agent.yaml`
- Data: `/var/lib/edgeguardian`
- Disk monitoring: `/`

```bash
# Create directories
sudo mkdir -p /etc/edgeguardian /var/lib/edgeguardian

# Write config
sudo tee /etc/edgeguardian/agent.yaml > /dev/null <<'EOF'
device_id: my-linux-box
controller_address: localhost
controller_port: 8443
log_level: debug
data_dir: /var/lib/edgeguardian

mqtt:
  broker_url: tcp://localhost:1883
  topic_root: edgeguardian

auth:
  enrollment_token: egt_xxxxx  # paste your token

health:
  disk_path: /
  port: 8484
EOF

# Run
sudo ./build/edgeguardian-agent-linux-amd64
```

> **Note:** `sudo` is needed for systemd service management. Health metrics (CPU/RAM/disk) work without root.

**Run as a systemd service (optional):**

```bash
sudo cp build/edgeguardian-agent-linux-amd64 /usr/local/bin/edgeguardian-agent
sudo cp deployments/edgeguardian-agent.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now edgeguardian-agent
journalctl -u edgeguardian-agent -f   # view logs
```

---

### Windows

**Default paths:**
- Config: `C:\ProgramData\EdgeGuardian\agent.yaml`
- Data: `C:\ProgramData\EdgeGuardian\data`
- Disk monitoring: `C:\`

```powershell
# Create directories
New-Item -ItemType Directory -Force -Path "C:\ProgramData\EdgeGuardian\data"

# Write config (paste this into C:\ProgramData\EdgeGuardian\agent.yaml)
```

```yaml
device_id: my-windows-pc
controller_address: localhost
controller_port: 8443
log_level: debug
data_dir: C:\ProgramData\EdgeGuardian\data

mqtt:
  broker_url: tcp://localhost:1883
  topic_root: edgeguardian

auth:
  enrollment_token: egt_xxxxx  # paste your token

health:
  disk_path: "C:\\"
  port: 8484
```

```powershell
# Run (as Administrator for full service control)
.\build\edgeguardian-agent-windows-amd64.exe -config "C:\ProgramData\EdgeGuardian\agent.yaml"
```

> **Note:** Run as Administrator for Windows service management and temperature monitoring. CPU/RAM/disk metrics work without elevation.

**Save logs to file:**

```powershell
.\build\edgeguardian-agent-windows-amd64.exe -config "C:\ProgramData\EdgeGuardian\agent.yaml" > agent.log 2>&1
```

---

### macOS

**Default paths:**
- Config: `/etc/edgeguardian/agent.yaml`
- Data: `/var/lib/edgeguardian`
- Disk monitoring: `/`

```bash
# Create directories
sudo mkdir -p /etc/edgeguardian /var/lib/edgeguardian

# Write config
sudo tee /etc/edgeguardian/agent.yaml > /dev/null <<'EOF'
device_id: my-mac
controller_address: localhost
controller_port: 8443
log_level: debug
data_dir: /var/lib/edgeguardian

mqtt:
  broker_url: tcp://localhost:1883
  topic_root: edgeguardian

auth:
  enrollment_token: egt_xxxxx  # paste your token

health:
  disk_path: /
  port: 8484
EOF

# Run
sudo ./build/edgeguardian-agent-darwin-arm64
```

> **Note:** macOS has no systemd. The agent runs as a foreground process. Use `launchd` for background operation.

---

### Android (via Termux or ADB)

**Default paths:**
- Config: `/data/local/tmp/edgeguardian/agent.yaml`
- Data: `/data/local/tmp/edgeguardian`
- Disk monitoring: `/data`

**Termux (no root):**

```bash
mkdir -p /data/local/tmp/edgeguardian

cat > /data/local/tmp/edgeguardian/agent.yaml <<'EOF'
device_id: my-android
controller_address: 192.168.1.100  # your host machine IP
controller_port: 8443
log_level: debug
data_dir: /data/local/tmp/edgeguardian

mqtt:
  broker_url: tcp://192.168.1.100:1883
  topic_root: edgeguardian

auth:
  enrollment_token: egt_xxxxx

health:
  disk_path: /data
  port: 8484
EOF

./edgeguardian-agent-android-arm64
```

**ADB push (from host):**

```bash
# Build
./scripts/build-agent.sh android arm64

# Push and run
adb push build/edgeguardian-agent-android-arm64 /data/local/tmp/edgeguardian/
adb shell chmod +x /data/local/tmp/edgeguardian/edgeguardian-agent-android-arm64
adb shell /data/local/tmp/edgeguardian/edgeguardian-agent-android-arm64
```

> **Note:** On Android, use your host machine's LAN IP (not `localhost`) for `controller_address` and `mqtt.broker_url`.

---

## 6. What Happens on Startup

1. **BoltDB opens** — local state persisted at `{data_dir}/agent.db`
2. **Enrollment** — agent sends `POST /api/v1/agent/enroll` with the enrollment token. The controller returns a device token (`edt_xxxxx`) which is saved to `{data_dir}/device-token`
3. **Heartbeat loop** — every 30s, sends CPU, RAM, disk, uptime, reconcile status
4. **Reconciliation loop** — fetches desired state from controller, diffs against actual, applies changes via plugins (services, files)
5. **MQTT connection** — subscribes to command topics for real-time operations

On subsequent restarts, the agent loads the saved device token and skips enrollment.

## 7. Viewing Logs

**Agent logs are JSON to stdout.** Example output:

```json
{"level":"info","msg":"agent starting","version":"0.3.0","device_id":"my-linux-box","os":"linux"}
{"level":"info","msg":"BoltDB opened","path":"/var/lib/edgeguardian/agent.db"}
{"level":"info","msg":"enrolled with controller"}
{"level":"info","msg":"device token saved","path":"/var/lib/edgeguardian/device-token"}
{"level":"info","msg":"heartbeat sent","cpu":12.3,"mem_used":8589934592}
{"level":"info","msg":"reconcile complete","status":"converged"}
```

**Useful approaches:**

```bash
# Pretty-print with jq
./edgeguardian-agent | jq .

# Filter errors only
./edgeguardian-agent 2>&1 | jq 'select(.level == "error")'

# Save to file and tail
./edgeguardian-agent > /var/log/edgeguardian.log 2>&1 &
tail -f /var/log/edgeguardian.log | jq .

# systemd (Linux)
journalctl -u edgeguardian-agent -f --output=json-pretty
```

Set `log_level: debug` in config for verbose output (reconciler diffs, MQTT messages, HTTP requests).

## 8. Watchdog (Optional)

The watchdog binary supervises the agent with crash recovery and OTA binary swap:

```bash
./edgeguardian-watchdog-linux-amd64 \
  -binary ./edgeguardian-agent-linux-amd64 \
  -config /etc/edgeguardian/agent.yaml \
  -data-dir /var/lib/edgeguardian
```

Behavior:
- Restarts the agent on crash (exponential backoff: 1s → 5min)
- **3 crashes in 5 minutes** → rolls back to `{binary}.bak`
- **Exit code 42** from agent → OTA binary swap (downloads new binary, restarts)
- Health checks: polls `http://127.0.0.1:8484/healthz` every 2s

## 9. Verify in Dashboard

Once the agent is running and enrolled:

1. Open the dashboard at `http://localhost:3000`
2. Go to **Devices** — your device appears with live status
3. Click the device to see:
   - Real-time CPU, memory, disk metrics
   - Connection status and uptime
   - Agent version and OS/architecture
   - Reconciliation status
   - Device logs (if log forwarding is enabled)

## Config Reference

| Field | Default | Description |
|-------|---------|-------------|
| `device_id` | hostname | Unique device identifier |
| `controller_address` | `localhost` | Controller host |
| `controller_port` | `8443` | Controller port |
| `reconcile_interval_seconds` | `30` | Seconds between reconciliation |
| `log_level` | `info` | `debug`, `info`, `warn`, `error` |
| `data_dir` | OS-specific | BoltDB, tokens, OTA cache |
| `mqtt.broker_url` | `tcp://localhost:1883` | MQTT broker address |
| `mqtt.topic_root` | `edgeguardian` | MQTT topic prefix |
| `health.port` | `8484` | Local health HTTP server |
| `health.disk_path` | OS-specific | Partition to monitor |
| `auth.enrollment_token` | — | One-time enrollment token |
| `auth.device_token` | — | Auto-populated after enrollment |
| `ota.enabled` | `false` | Enable OTA updates |
| `log_forwarding.enabled` | `false` | Forward logs via MQTT |