#!/usr/bin/env bash
# =============================================================================
# EdgeGuardian OTA E2E Test Script  (runs under WSL bash from PowerShell)
#
# All Windows tools (go, gradle, node, docker) are invoked via cmd.exe
# since WSL bash cannot run .exe directly on all WSL versions.
#
# Usage: bash test/run-e2e.sh
# =============================================================================
set -euo pipefail

# ---- Path setup ----
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEST_DIR="$ROOT/test"
MAIN_GO="$ROOT/agent/cmd/agent/main.go"

# Convert WSL path to Windows path: /mnt/c/foo -> C:\foo
to_win() { echo "$1" | sed 's|^/mnt/c|C:|; s|^/c|C:|; s|/|\\|g'; }

WIN_ROOT=$(to_win "$ROOT")
WIN_TEST=$(to_win "$TEST_DIR")
WIN_AGENT="$WIN_ROOT\\agent"

OLD_VERSION="0.3.0"
NEW_VERSION="0.4.0"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
step() { echo -e "\n${CYAN}==> $1${NC}"; }
ok()   { echo -e "${GREEN}    OK: $1${NC}"; }
warn() { echo -e "${YELLOW}    WARN: $1${NC}"; }
fail() { echo -e "${RED}    FAIL: $1${NC}"; exit 1; }

# Run a Windows command via cmd.exe (handles paths, .exe, etc.)
win() { cmd.exe /c "$*" 2>&1; }

# Track background PIDs for cleanup
PIDS=()
cleanup() {
    echo ""
    step "Shutting down"
    for pid in "${PIDS[@]}"; do
        kill "$pid" 2>/dev/null || true
    done
    # Also kill any leftover Windows processes we started
    cmd.exe /c "taskkill /F /IM edgeguardian-watchdog.exe" 2>/dev/null || true
    cmd.exe /c "taskkill /F /IM edgeguardian-agent.exe" 2>/dev/null || true
    ok "Stopped background processes"
    echo -e "${YELLOW}Docker containers left running. Stop with:${NC}"
    echo "  docker compose -f deployments/docker-compose.yml down"
}
trap cleanup EXIT

# ---- Verify Go ----
step "Checking tools"
win go version || fail "Go not found"
ok "Go found"

# ---- Clean previous run ----
step "Cleaning previous test artifacts"
rm -rf "$TEST_DIR/data"
rm -f  "$TEST_DIR/edgeguardian-agent.exe" \
       "$TEST_DIR/edgeguardian-agent.exe.bak" \
       "$TEST_DIR/edgeguardian-agent.exe.failed" \
       "$TEST_DIR/edgeguardian-watchdog.exe" \
       "$TEST_DIR/agent-update.exe"
mkdir -p "$TEST_DIR/data"
ok "Clean"

# ---- Build agent v0.3.0 ----
step "Building agent $OLD_VERSION (initial version)"

CURRENT=$(grep -oP 'const agentVersion = "\K[^"]+' "$MAIN_GO")
sed -i.bak "s/const agentVersion = \"$CURRENT\"/const agentVersion = \"$OLD_VERSION\"/" "$MAIN_GO"

win "cd /d $WIN_AGENT && go build -o $WIN_TEST\\edgeguardian-agent.exe ./cmd/agent/"
ok "Built edgeguardian-agent.exe ($OLD_VERSION)"

# ---- Build agent v0.4.0 ----
step "Building agent $NEW_VERSION (update artifact)"
sed -i "s/const agentVersion = \"$OLD_VERSION\"/const agentVersion = \"$NEW_VERSION\"/" "$MAIN_GO"

win "cd /d $WIN_AGENT && go build -o $WIN_TEST\\agent-update.exe ./cmd/agent/"
ok "Built agent-update.exe ($NEW_VERSION)"

# Restore original source
mv "$MAIN_GO.bak" "$MAIN_GO"

# ---- Build watchdog ----
step "Building watchdog"
win "cd /d $WIN_AGENT && go build -o $WIN_TEST\\edgeguardian-watchdog.exe ./cmd/watchdog/"
ok "Built edgeguardian-watchdog.exe"

# ---- Docker infrastructure ----
step "Starting Docker infrastructure (PostgreSQL, Keycloak, EMQX)"
cd "$ROOT"
win "cd /d $WIN_ROOT && docker compose -f deployments\\docker-compose.yml up -d postgres keycloak emqx" | tail -5
ok "Docker containers starting"

step "Waiting for PostgreSQL"
for i in $(seq 1 30); do
    if win "docker exec edgeguardian-postgres pg_isready -U edgeguardian" >/dev/null 2>&1; then
        ok "PostgreSQL ready"; break
    fi
    [ "$i" -eq 30 ] && fail "PostgreSQL not ready after 30s"
    sleep 1
done

step "Waiting for Keycloak"
for i in $(seq 1 30); do
    if win "curl -sf http://localhost:9090/health/ready" >/dev/null 2>&1; then
        ok "Keycloak ready"; break
    fi
    if [ "$i" -eq 30 ]; then warn "Keycloak not ready after 60s (continuing anyway)"; fi
    sleep 2
done

# ---- Controller ----
step "Starting Spring Boot controller"
cmd.exe /c "cd /d $WIN_ROOT\\controller && gradlew.bat bootRun" > "$TEST_DIR/controller.log" 2>&1 &
PIDS+=($!)
ok "Controller starting (log: test/controller.log)"

step "Waiting for controller"
for i in $(seq 1 60); do
    if win "curl -sf http://localhost:8443/actuator/health" >/dev/null 2>&1; then
        ok "Controller ready at http://localhost:8443"; break
    fi
    [ "$i" -eq 60 ] && fail "Controller not ready after 120s. Check test/controller.log"
    sleep 2
done

# ---- Dashboard ----
step "Starting Next.js dashboard (port 3001)"
cmd.exe /c "cd /d $WIN_ROOT\\ui && npx next dev --turbopack --port 3001" > "$TEST_DIR/dashboard.log" 2>&1 &
PIDS+=($!)
ok "Dashboard starting (log: test/dashboard.log)"
sleep 5

# ---- Summary ----
echo ""
echo -e "${GREEN}+--------------------------------------------------------------+${NC}"
echo -e "${GREEN}|  EdgeGuardian OTA E2E Test Environment Ready                 |${NC}"
echo -e "${GREEN}+--------------------------------------------------------------+${NC}"
echo -e "${GREEN}|  Controller:  http://localhost:8443                          |${NC}"
echo -e "${GREEN}|  Dashboard:   http://localhost:3001                          |${NC}"
echo -e "${GREEN}|  Health:      http://127.0.0.1:8484/healthz                 |${NC}"
echo -e "${GREEN}|  Keycloak:    http://localhost:9090  (admin/admin)           |${NC}"
echo -e "${GREEN}|  EMQX:        http://localhost:18083 (admin/public)          |${NC}"
echo -e "${GREEN}|                                                              |${NC}"
echo -e "${GREEN}|  Agent v${OLD_VERSION} running via watchdog                         |${NC}"
echo -e "${GREEN}|  Upload test/agent-update.exe (${NEW_VERSION}) in dashboard OTA    |${NC}"
echo -e "${GREEN}|                                                              |${NC}"
echo -e "${GREEN}|  Ctrl+C to stop everything                                  |${NC}"
echo -e "${GREEN}+--------------------------------------------------------------+${NC}"
echo ""

# ---- Watchdog (foreground, blocks until Ctrl+C) ----
win "cd /d $WIN_ROOT && $WIN_TEST\\edgeguardian-watchdog.exe --binary $WIN_TEST\\edgeguardian-agent.exe --config $WIN_TEST\\agent.yaml --data-dir $WIN_TEST\\data"