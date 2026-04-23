#!/usr/bin/env bash
# EdgeGuardian local development launcher.
# Works on Linux, macOS, and Windows (Git Bash / MSYS2).
#
# Usage:
#   ./scripts/dev.sh              # start everything
#   ./scripts/dev.sh start        # same as above
#   ./scripts/dev.sh stop         # tear down all components
#   ./scripts/dev.sh status       # show what's running
#   ./scripts/dev.sh logs         # tail controller + UI logs
#   ./scripts/dev.sh restart      # stop then start

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PID_DIR="$ROOT_DIR/.dev-pids"
LOG_DIR="$ROOT_DIR/.dev-logs"

# ── Colours (disabled if not a terminal) ───────────────────────────
if [ -t 1 ]; then
  RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
  CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
else
  RED=''; GREEN=''; YELLOW=''; CYAN=''; BOLD=''; NC=''
fi

info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }
header(){ echo -e "\n${BOLD}${CYAN}=== $* ===${NC}"; }

# ── Prerequisite checks ───────────────────────────────────────────
check_prereqs() {
  header "Checking prerequisites"
  local missing=0

  # Docker
  if command -v docker &>/dev/null; then
    if docker info &>/dev/null; then
      info "Docker: $(docker --version | head -1)"
    else
      error "Docker is installed but the daemon is not running"
      missing=1
    fi
  else
    error "Docker is not installed - needed for infrastructure (PostgreSQL, Keycloak, EMQX, MinIO, Loki, Grafana)"
    missing=1
  fi

  # docker compose (v2 plugin or standalone)
  if docker compose version &>/dev/null; then
    info "Docker Compose: $(docker compose version --short 2>/dev/null || docker compose version)"
  elif command -v docker-compose &>/dev/null; then
    info "Docker Compose (standalone): $(docker-compose --version)"
  else
    error "Docker Compose is not installed"
    missing=1
  fi

  # Go
  if command -v go &>/dev/null; then
    info "Go: $(go version | awk '{print $3}')"
  else
    error "Go is not installed - needed to build the agent"
    missing=1
  fi

  # Java 21+
  if command -v java &>/dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/' | cut -d. -f1)
    if [ "$JAVA_VER" -ge 21 ] 2>/dev/null; then
      info "Java: $(java -version 2>&1 | head -1)"
    else
      error "Java 21+ required, found version $JAVA_VER"
      missing=1
    fi
  else
    error "Java is not installed - needed to build the controller"
    missing=1
  fi

  # Node.js
  if command -v node &>/dev/null; then
    info "Node.js: $(node --version)"
  else
    error "Node.js is not installed - needed for the dashboard"
    missing=1
  fi

  # npm
  if command -v npm &>/dev/null; then
    info "npm: $(npm --version)"
  else
    error "npm is not installed"
    missing=1
  fi

  if [ "$missing" -ne 0 ]; then
    echo ""
    error "Missing prerequisites. Please install them and try again."
    exit 1
  fi
  info "All prerequisites satisfied."
}

# ── Docker Compose helper (v2 plugin vs standalone) ───────────────
dc() {
  if docker compose version &>/dev/null; then
    docker compose -f "$ROOT_DIR/deployments/docker-compose.yml" "$@"
  else
    docker-compose -f "$ROOT_DIR/deployments/docker-compose.yml" "$@"
  fi
}

# ── Start infrastructure ──────────────────────────────────────────
start_infra() {
  header "Starting infrastructure (Docker)"
  dc up -d

  info "Waiting for services to become healthy..."
  local retries=60
  local ready=false
  for i in $(seq 1 $retries); do
    # Check the critical services
    local pg_ok=false kc_ok=false emqx_ok=false
    if docker inspect --format='{{.State.Health.Status}}' edgeguardian-postgres 2>/dev/null | grep -q healthy; then pg_ok=true; fi
    if docker inspect --format='{{.State.Health.Status}}' edgeguardian-emqx 2>/dev/null | grep -q healthy; then emqx_ok=true; fi
    if docker inspect --format='{{.State.Health.Status}}' edgeguardian-keycloak 2>/dev/null | grep -q healthy; then kc_ok=true; fi

    if $pg_ok && $emqx_ok && $kc_ok; then
      ready=true
      break
    fi

    # Progress indicator
    local status=""
    $pg_ok   && status+=" pg:ok"   || status+=" pg:wait"
    $emqx_ok && status+=" emqx:ok" || status+=" emqx:wait"
    $kc_ok   && status+=" kc:ok"   || status+=" kc:wait"
    printf "\r  [%2d/%d]%s" "$i" "$retries" "$status"
    sleep 5
  done
  echo ""

  if $ready; then
    info "Infrastructure is ready."
  else
    warn "Some services may not be healthy yet. Continuing anyway..."
    warn "Run 'docker ps' to check container status."
  fi
}

# ── Build agent ───────────────────────────────────────────────────
build_agent() {
  header "Building Go agent (current platform)"
  bash "$SCRIPT_DIR/build-agent.sh"
}

# ── Start controller ─────────────────────────────────────────────
start_controller() {
  header "Starting Spring Boot controller"
  mkdir -p "$PID_DIR" "$LOG_DIR"

  if [ -f "$PID_DIR/controller.pid" ]; then
    local pid
    pid=$(cat "$PID_DIR/controller.pid")
    if kill -0 "$pid" 2>/dev/null; then
      info "Controller already running (PID $pid)"
      return
    fi
  fi

  local gradlew="$ROOT_DIR/controller/gradlew"
  if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "mingw"* || "$OSTYPE" == "cygwin"* ]]; then
    gradlew="$ROOT_DIR/controller/gradlew.bat"
  fi

  info "Starting controller (log: .dev-logs/controller.log)"
  cd "$ROOT_DIR/controller"
  "$gradlew" bootRun > "$LOG_DIR/controller.log" 2>&1 &
  local pid=$!
  echo "$pid" > "$PID_DIR/controller.pid"
  cd "$ROOT_DIR"

  # Wait for controller to be reachable
  info "Waiting for controller on port 8443..."
  local retries=60
  for i in $(seq 1 $retries); do
    if curl -s -o /dev/null -w '' http://localhost:8443/actuator/health 2>/dev/null; then
      info "Controller is up (PID $pid)"
      return
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      error "Controller process died. Check .dev-logs/controller.log"
      exit 1
    fi
    printf "\r  [%2d/%d] waiting..." "$i" "$retries"
    sleep 3
  done
  echo ""
  warn "Controller may not be fully ready yet. Check .dev-logs/controller.log"
}

# ── Start UI ──────────────────────────────────────────────────────
start_ui() {
  header "Starting Next.js dashboard"
  mkdir -p "$PID_DIR" "$LOG_DIR"

  if [ -f "$PID_DIR/ui.pid" ]; then
    local pid
    pid=$(cat "$PID_DIR/ui.pid")
    if kill -0 "$pid" 2>/dev/null; then
      info "Dashboard already running (PID $pid)"
      return
    fi
  fi

  # Install deps if needed
  if [ ! -d "$ROOT_DIR/ui/node_modules" ]; then
    info "Installing npm dependencies..."
    cd "$ROOT_DIR/ui"
    npm install
    cd "$ROOT_DIR"
  fi

  info "Starting dashboard (log: .dev-logs/ui.log)"
  cd "$ROOT_DIR/ui"
  npm run dev > "$LOG_DIR/ui.log" 2>&1 &
  local pid=$!
  echo "$pid" > "$PID_DIR/ui.pid"
  cd "$ROOT_DIR"

  # Brief wait for dev server
  local retries=20
  for i in $(seq 1 $retries); do
    if curl -s -o /dev/null http://localhost:3000 2>/dev/null; then
      info "Dashboard is up (PID $pid)"
      return
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      error "Dashboard process died. Check .dev-logs/ui.log"
      exit 1
    fi
    printf "\r  [%2d/%d] waiting..." "$i" "$retries"
    sleep 2
  done
  echo ""
  warn "Dashboard may still be starting. Check .dev-logs/ui.log"
}

# ── Stop everything ──────────────────────────────────────────────
stop_all() {
  header "Stopping EdgeGuardian"

  # Stop UI
  if [ -f "$PID_DIR/ui.pid" ]; then
    local pid
    pid=$(cat "$PID_DIR/ui.pid")
    if kill -0 "$pid" 2>/dev/null; then
      info "Stopping dashboard (PID $pid)..."
      kill "$pid" 2>/dev/null || true
      # Also kill child processes (node spawns children)
      pkill -P "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_DIR/ui.pid"
  else
    info "Dashboard not running"
  fi

  # Stop controller
  if [ -f "$PID_DIR/controller.pid" ]; then
    local pid
    pid=$(cat "$PID_DIR/controller.pid")
    if kill -0 "$pid" 2>/dev/null; then
      info "Stopping controller (PID $pid)..."
      kill "$pid" 2>/dev/null || true
      pkill -P "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_DIR/controller.pid"
  else
    info "Controller not running"
  fi

  # Stop Docker infrastructure
  info "Stopping Docker infrastructure..."
  dc down

  info "All stopped."
}

# ── Status ────────────────────────────────────────────────────────
show_status() {
  header "EdgeGuardian Status"

  # Docker
  echo -e "\n${BOLD}Infrastructure (Docker):${NC}"
  local services=("edgeguardian-postgres" "edgeguardian-keycloak" "edgeguardian-emqx" "edgeguardian-minio" "edgeguardian-loki" "edgeguardian-grafana")
  for svc in "${services[@]}"; do
    local state
    state=$(docker inspect --format='{{.State.Status}} ({{.State.Health.Status}})' "$svc" 2>/dev/null || echo "not running")
    local name=${svc#edgeguardian-}
    printf "  %-12s %s\n" "$name:" "$state"
  done

  # Controller
  echo -e "\n${BOLD}Controller:${NC}"
  if [ -f "$PID_DIR/controller.pid" ]; then
    local pid
    pid=$(cat "$PID_DIR/controller.pid")
    if kill -0 "$pid" 2>/dev/null; then
      info "  Running (PID $pid)"
    else
      warn "  PID file exists but process is dead"
    fi
  else
    echo "  Not running"
  fi

  # UI
  echo -e "\n${BOLD}Dashboard:${NC}"
  if [ -f "$PID_DIR/ui.pid" ]; then
    local pid
    pid=$(cat "$PID_DIR/ui.pid")
    if kill -0 "$pid" 2>/dev/null; then
      info "  Running (PID $pid)"
    else
      warn "  PID file exists but process is dead"
    fi
  else
    echo "  Not running"
  fi
}

# ── Tail logs ─────────────────────────────────────────────────────
tail_logs() {
  header "Tailing logs (Ctrl+C to stop)"
  tail -f "$LOG_DIR/controller.log" "$LOG_DIR/ui.log" 2>/dev/null || {
    error "No log files found. Is anything running?"
    exit 1
  }
}

# ── Print URLs ────────────────────────────────────────────────────
print_urls() {
  header "EdgeGuardian is running"
  echo ""
  echo -e "  ${BOLD}Dashboard:${NC}        http://localhost:3000"
  echo -e "  ${BOLD}Controller API:${NC}   http://localhost:8443"
  echo -e "  ${BOLD}Keycloak:${NC}         http://localhost:9090  (admin / admin)"
  echo -e "  ${BOLD}EMQX Dashboard:${NC}   http://localhost:18083 (admin / public)"
  echo -e "  ${BOLD}MinIO Console:${NC}    http://localhost:9001  (edgeguardian / edgeguardian-dev)"
  echo -e "  ${BOLD}Grafana:${NC}          http://localhost:3002  (admin / admin)"
  echo -e "  ${BOLD}Loki:${NC}             http://localhost:3100"
  echo ""
  echo -e "  Logs:  ${CYAN}./scripts/dev.sh logs${NC}"
  echo -e "  Stop:  ${CYAN}./scripts/dev.sh stop${NC}"
  echo ""
}

# ── Main ──────────────────────────────────────────────────────────
CMD="${1:-start}"

case "$CMD" in
  start)
    check_prereqs
    start_infra
    build_agent
    start_controller
    start_ui
    print_urls
    ;;
  stop)
    stop_all
    ;;
  restart)
    stop_all
    sleep 2
    check_prereqs
    start_infra
    build_agent
    start_controller
    start_ui
    print_urls
    ;;
  status)
    show_status
    ;;
  logs)
    tail_logs
    ;;
  *)
    echo "Usage: $0 {start|stop|restart|status|logs}"
    exit 1
    ;;
esac
