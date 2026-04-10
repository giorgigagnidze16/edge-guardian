#Requires -Version 5.1
<#
.SYNOPSIS
    EdgeGuardian local development launcher (PowerShell).
.DESCRIPTION
    Starts all EdgeGuardian components for local development on Windows.
.PARAMETER Command
    start (default), stop, restart, status, logs
.EXAMPLE
    .\scripts\dev.ps1             # start everything
    .\scripts\dev.ps1 stop        # tear down
    .\scripts\dev.ps1 status      # show what's running
    .\scripts\dev.ps1 logs        # tail controller + UI logs
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet("start", "stop", "restart", "status", "logs")]
    [string]$Command = "start"
)

$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$ScriptsDir = Join-Path $RootDir "scripts"
$PidDir = Join-Path $RootDir ".dev-pids"
$LogDir = Join-Path $RootDir ".dev-logs"
$ComposeFile = Join-Path $RootDir "deployments\docker-compose.yml"

# ── Helpers ──────────────────────────────────────────────────────
function Write-Info  { param($Msg) Write-Host "[INFO]  $Msg" -ForegroundColor Green }
function Write-Warn  { param($Msg) Write-Host "[WARN]  $Msg" -ForegroundColor Yellow }
function Write-Err   { param($Msg) Write-Host "[ERROR] $Msg" -ForegroundColor Red }
function Write-Header { param($Msg) Write-Host "`n=== $Msg ===" -ForegroundColor Cyan }

function Test-ProcessAlive {
    param([int]$Pid)
    try { $null = Get-Process -Id $Pid -ErrorAction Stop; return $true }
    catch { return $false }
}

# ── Docker Compose wrapper ────────────────────────────────────────
function Invoke-DC {
    param([Parameter(ValueFromRemainingArguments)]$Args)
    # Try v2 plugin first, fall back to standalone
    $result = & docker compose version 2>&1
    if ($LASTEXITCODE -eq 0) {
        & docker compose -f $ComposeFile @Args
    } else {
        & docker-compose -f $ComposeFile @Args
    }
}

# ── Prerequisites ─────────────────────────────────────────────────
function Test-Prerequisites {
    Write-Header "Checking prerequisites"
    $missing = 0

    # Docker
    if (Get-Command docker -ErrorAction SilentlyContinue) {
        $null = & docker info 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Info "Docker: $(docker --version)"
        } else {
            Write-Err "Docker is installed but the daemon is not running"
            $missing++
        }
    } else {
        Write-Err "Docker is not installed"
        $missing++
    }

    # Docker Compose
    $null = & docker compose version 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Info "Docker Compose: $(docker compose version --short)"
    } elseif (Get-Command docker-compose -ErrorAction SilentlyContinue) {
        Write-Info "Docker Compose (standalone): $(docker-compose --version)"
    } else {
        Write-Err "Docker Compose is not installed"
        $missing++
    }

    # Go
    if (Get-Command go -ErrorAction SilentlyContinue) {
        Write-Info "Go: $(go version)"
    } else {
        Write-Err "Go is not installed - needed to build the agent"
        $missing++
    }

    # Java 21+
    if (Get-Command java -ErrorAction SilentlyContinue) {
        $javaVer = & java -version 2>&1 | Select-Object -First 1
        $verMatch = [regex]::Match($javaVer, '"(\d+)')
        if ($verMatch.Success -and [int]$verMatch.Groups[1].Value -ge 21) {
            Write-Info "Java: $javaVer"
        } else {
            Write-Err "Java 21+ required, found: $javaVer"
            $missing++
        }
    } else {
        Write-Err "Java is not installed - needed for the controller"
        $missing++
    }

    # Node.js
    if (Get-Command node -ErrorAction SilentlyContinue) {
        Write-Info "Node.js: $(node --version)"
    } else {
        Write-Err "Node.js is not installed - needed for the dashboard"
        $missing++
    }

    if ($missing -gt 0) {
        Write-Host ""
        Write-Err "Missing prerequisites. Please install them and try again."
        exit 1
    }
    Write-Info "All prerequisites satisfied."
}

# ── Infrastructure ────────────────────────────────────────────────
function Start-Infra {
    Write-Header "Starting infrastructure (Docker)"
    Invoke-DC up -d

    Write-Info "Waiting for services to become healthy..."
    $maxRetries = 60
    for ($i = 1; $i -le $maxRetries; $i++) {
        $pgOk = (docker inspect --format='{{.State.Health.Status}}' edgeguardian-postgres 2>$null) -eq "healthy"
        $emqxOk = (docker inspect --format='{{.State.Health.Status}}' edgeguardian-emqx 2>$null) -eq "healthy"
        $kcOk = (docker inspect --format='{{.State.Health.Status}}' edgeguardian-keycloak 2>$null) -eq "healthy"

        if ($pgOk -and $emqxOk -and $kcOk) {
            Write-Info "Infrastructure is ready."
            return
        }

        $status = "pg:$(if($pgOk){'ok'}else{'wait'}) emqx:$(if($emqxOk){'ok'}else{'wait'}) kc:$(if($kcOk){'ok'}else{'wait'})"
        Write-Host "`r  [$i/$maxRetries] $status" -NoNewline
        Start-Sleep -Seconds 5
    }
    Write-Host ""
    Write-Warn "Some services may not be healthy yet. Run 'docker ps' to check."
}

# ── Build agent ───────────────────────────────────────────────────
function Build-Agent {
    Write-Header "Building Go agent (current platform)"
    Push-Location (Join-Path $RootDir "agent")
    try {
        $outputDir = Join-Path $RootDir "build"
        New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

        $os = go env GOOS
        $arch = go env GOARCH
        $version = "0.2.0"
        $ext = if ($os -eq "windows") { ".exe" } else { "" }

        Write-Info "Building agent ($os/$arch)..."
        $env:CGO_ENABLED = "0"
        & go build -ldflags="-s -w -X main.agentVersion=$version" `
            -o "$outputDir\edgeguardian-agent-$os-$arch$ext" ./cmd/agent
        if ($LASTEXITCODE -ne 0) { throw "Agent build failed" }

        Write-Info "Building watchdog ($os/$arch)..."
        & go build -ldflags="-s -w" `
            -o "$outputDir\edgeguardian-watchdog-$os-$arch$ext" ./cmd/watchdog
        if ($LASTEXITCODE -ne 0) { throw "Watchdog build failed" }

        Get-ChildItem "$outputDir\edgeguardian-*-$os-$arch*" | ForEach-Object {
            Write-Info "  $($_.Name) ($([math]::Round($_.Length / 1MB, 2)) MB)"
        }
    } finally {
        Pop-Location
    }
}

# ── Controller ────────────────────────────────────────────────────
function Start-Controller {
    Write-Header "Starting Spring Boot controller"
    New-Item -ItemType Directory -Path $PidDir -Force | Out-Null
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

    $pidFile = Join-Path $PidDir "controller.pid"
    if (Test-Path $pidFile) {
        $pid = [int](Get-Content $pidFile)
        if (Test-ProcessAlive $pid) {
            Write-Info "Controller already running (PID $pid)"
            return
        }
    }

    $logFile = Join-Path $LogDir "controller.log"
    Write-Info "Starting controller (log: .dev-logs\controller.log)"

    $gradlew = Join-Path $RootDir "controller\gradlew.bat"
    $proc = Start-Process -FilePath $gradlew -ArgumentList "bootRun" `
        -WorkingDirectory (Join-Path $RootDir "controller") `
        -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $LogDir "controller-err.log") `
        -PassThru -WindowStyle Hidden
    $proc.Id | Out-File -FilePath $pidFile -Encoding ascii

    Write-Info "Waiting for controller on port 8443..."
    $maxRetries = 60
    for ($i = 1; $i -le $maxRetries; $i++) {
        try {
            $null = Invoke-WebRequest -Uri "http://localhost:8443/actuator/health" -UseBasicParsing -TimeoutSec 2
            Write-Info "Controller is up (PID $($proc.Id))"
            return
        } catch {}

        if (-not (Test-ProcessAlive $proc.Id)) {
            Write-Err "Controller process died. Check .dev-logs\controller.log"
            exit 1
        }
        Write-Host "`r  [$i/$maxRetries] waiting..." -NoNewline
        Start-Sleep -Seconds 3
    }
    Write-Host ""
    Write-Warn "Controller may not be fully ready. Check .dev-logs\controller.log"
}

# ── Dashboard ─────────────────────────────────────────────────────
function Start-Dashboard {
    Write-Header "Starting Next.js dashboard"
    New-Item -ItemType Directory -Path $PidDir -Force | Out-Null
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

    $pidFile = Join-Path $PidDir "ui.pid"
    if (Test-Path $pidFile) {
        $pid = [int](Get-Content $pidFile)
        if (Test-ProcessAlive $pid) {
            Write-Info "Dashboard already running (PID $pid)"
            return
        }
    }

    $uiDir = Join-Path $RootDir "ui"
    if (-not (Test-Path (Join-Path $uiDir "node_modules"))) {
        Write-Info "Installing npm dependencies..."
        Push-Location $uiDir
        & npm install
        Pop-Location
    }

    $logFile = Join-Path $LogDir "ui.log"
    Write-Info "Starting dashboard (log: .dev-logs\ui.log)"

    $proc = Start-Process -FilePath "npm" -ArgumentList "run", "dev" `
        -WorkingDirectory $uiDir `
        -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $LogDir "ui-err.log") `
        -PassThru -WindowStyle Hidden
    $proc.Id | Out-File -FilePath $pidFile -Encoding ascii

    $maxRetries = 20
    for ($i = 1; $i -le $maxRetries; $i++) {
        try {
            $null = Invoke-WebRequest -Uri "http://localhost:3000" -UseBasicParsing -TimeoutSec 2
            Write-Info "Dashboard is up (PID $($proc.Id))"
            return
        } catch {}

        if (-not (Test-ProcessAlive $proc.Id)) {
            Write-Err "Dashboard process died. Check .dev-logs\ui.log"
            exit 1
        }
        Write-Host "`r  [$i/$maxRetries] waiting..." -NoNewline
        Start-Sleep -Seconds 2
    }
    Write-Host ""
    Write-Warn "Dashboard may still be starting. Check .dev-logs\ui.log"
}

# ── Stop ──────────────────────────────────────────────────────────
function Stop-All {
    Write-Header "Stopping EdgeGuardian"

    # Stop UI
    $pidFile = Join-Path $PidDir "ui.pid"
    if (Test-Path $pidFile) {
        $pid = [int](Get-Content $pidFile)
        if (Test-ProcessAlive $pid) {
            Write-Info "Stopping dashboard (PID $pid)..."
            Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
            # Kill child processes
            Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $pid } |
                ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        }
        Remove-Item $pidFile -Force
    } else {
        Write-Info "Dashboard not running"
    }

    # Stop Controller
    $pidFile = Join-Path $PidDir "controller.pid"
    if (Test-Path $pidFile) {
        $pid = [int](Get-Content $pidFile)
        if (Test-ProcessAlive $pid) {
            Write-Info "Stopping controller (PID $pid)..."
            Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
            Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $pid } |
                ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        }
        Remove-Item $pidFile -Force
    } else {
        Write-Info "Controller not running"
    }

    # Stop Docker
    Write-Info "Stopping Docker infrastructure..."
    Invoke-DC down

    Write-Info "All stopped."
}

# ── Status ────────────────────────────────────────────────────────
function Show-Status {
    Write-Header "EdgeGuardian Status"

    Write-Host "`nInfrastructure (Docker):" -ForegroundColor White
    $services = @("edgeguardian-postgres", "edgeguardian-keycloak", "edgeguardian-emqx",
                   "edgeguardian-minio", "edgeguardian-loki", "edgeguardian-grafana")
    foreach ($svc in $services) {
        $state = docker inspect --format='{{.State.Status}} ({{.State.Health.Status}})' $svc 2>$null
        if (-not $state) { $state = "not running" }
        $name = $svc -replace "edgeguardian-", ""
        Write-Host ("  {0,-12} {1}" -f "${name}:", $state)
    }

    Write-Host "`nController:" -ForegroundColor White
    $pidFile = Join-Path $PidDir "controller.pid"
    if (Test-Path $pidFile) {
        $pid = [int](Get-Content $pidFile)
        if (Test-ProcessAlive $pid) {
            Write-Info "  Running (PID $pid)"
        } else {
            Write-Warn "  PID file exists but process is dead"
        }
    } else {
        Write-Host "  Not running"
    }

    Write-Host "`nDashboard:" -ForegroundColor White
    $pidFile = Join-Path $PidDir "ui.pid"
    if (Test-Path $pidFile) {
        $pid = [int](Get-Content $pidFile)
        if (Test-ProcessAlive $pid) {
            Write-Info "  Running (PID $pid)"
        } else {
            Write-Warn "  PID file exists but process is dead"
        }
    } else {
        Write-Host "  Not running"
    }
}

# ── Logs ──────────────────────────────────────────────────────────
function Show-Logs {
    Write-Header "Tailing logs (Ctrl+C to stop)"
    $controllerLog = Join-Path $LogDir "controller.log"
    $uiLog = Join-Path $LogDir "ui.log"

    if (-not (Test-Path $controllerLog) -and -not (Test-Path $uiLog)) {
        Write-Err "No log files found. Is anything running?"
        exit 1
    }

    # Use Get-Content -Wait to tail both files
    $files = @()
    if (Test-Path $controllerLog) { $files += $controllerLog }
    if (Test-Path $uiLog) { $files += $uiLog }
    Get-Content -Path $files -Wait -Tail 50
}

# ── URLs ──────────────────────────────────────────────────────────
function Show-Urls {
    Write-Header "EdgeGuardian is running"
    Write-Host ""
    Write-Host "  Dashboard:        http://localhost:3000"
    Write-Host "  Controller API:   http://localhost:8443"
    Write-Host "  Keycloak:         http://localhost:9090  (admin / admin)"
    Write-Host "  EMQX Dashboard:   http://localhost:18083 (admin / public)"
    Write-Host "  MinIO Console:    http://localhost:9001  (edgeguardian / edgeguardian-dev)"
    Write-Host "  Grafana:          http://localhost:3002  (admin / admin)"
    Write-Host "  Loki:             http://localhost:3100"
    Write-Host ""
    Write-Host "  Logs:  .\scripts\dev.ps1 logs" -ForegroundColor Cyan
    Write-Host "  Stop:  .\scripts\dev.ps1 stop" -ForegroundColor Cyan
    Write-Host ""
}

# ── Main ──────────────────────────────────────────────────────────
switch ($Command) {
    "start" {
        Test-Prerequisites
        Start-Infra
        Build-Agent
        Start-Controller
        Start-Dashboard
        Show-Urls
    }
    "stop" {
        Stop-All
    }
    "restart" {
        Stop-All
        Start-Sleep -Seconds 2
        Test-Prerequisites
        Start-Infra
        Build-Agent
        Start-Controller
        Start-Dashboard
        Show-Urls
    }
    "status" {
        Show-Status
    }
    "logs" {
        Show-Logs
    }
}
