param(
    [string]$Namespace = "edgeguardian",
    [switch]$SkipBuild,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$RepoRoot  = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ChartDir  = Join-Path $RepoRoot "deployments\helm\edgeguardian"
$CtrlDir   = Join-Path $RepoRoot "controller"
$UiDir     = Join-Path $RepoRoot "ui"

function Require-Cmd([string]$name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "'$name' not found in PATH"
    }
}

Require-Cmd kubectl
Require-Cmd helm
Require-Cmd minikube
Require-Cmd docker

Write-Host ">> Ensuring minikube is running"
$status = (& minikube status 2>&1 | Out-String)
if ($status -notmatch "host:\s*Running") {
    minikube start --cpus=4 --memory=8g
}

Write-Host ">> Pointing docker at minikube's daemon"
& minikube -p minikube docker-env --shell powershell | Invoke-Expression

if (-not $SkipBuild) {
    Write-Host ">> Building controller image"
    Push-Location $CtrlDir
    try { & ./gradlew.bat bootBuildImage } finally { Pop-Location }

    Write-Host ">> Building UI image"
    Push-Location $UiDir
    try { docker build -t edgeguardian/ui:latest . } finally { Pop-Location }
}

$MinikubeIp = (& minikube ip).Trim()
if (-not $MinikubeIp) { throw "Could not determine minikube IP" }
Write-Host ">> minikube IP: $MinikubeIp"

$UiUrl       = "http://${MinikubeIp}:30080"
$KeycloakUrl = "http://${MinikubeIp}:30090/realms/edgeguardian"

if ($Clean) {
    Write-Host ">> Removing existing release + namespace"
    helm uninstall edgeguardian --namespace $Namespace 2>$null | Out-Null
    kubectl delete namespace $Namespace --wait=true 2>$null | Out-Null
}

Write-Host ">> Installing chart with UI=$UiUrl, Keycloak=$KeycloakUrl"
helm upgrade --install edgeguardian $ChartDir `
    --namespace $Namespace --create-namespace `
    --set "ui.nextAuthUrl=$UiUrl" `
    --set "ui.keycloakIssuerUrl=$KeycloakUrl" `
    --wait --timeout 15m

Write-Host ""
Write-Host "=============================================================="
Write-Host "EdgeGuardian is up at:"
Write-Host "  UI         $UiUrl"
Write-Host "  Keycloak   http://${MinikubeIp}:30090   (admin/admin)"
Write-Host "  Controller http://${MinikubeIp}:30443"
Write-Host "  EMQX mTLS  ssl://${MinikubeIp}:30883"
Write-Host "  EMQX boot  tcp://${MinikubeIp}:31883"
Write-Host "=============================================================="
