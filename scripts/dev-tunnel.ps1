# Start background port-forwards so an agent running on localhost can reach
# the minikube-hosted controller + EMQX broker. Required for dev on Windows
# where the docker-driver minikube IP isn't routable from the host.
#
# Usage (from any PowerShell window, no admin needed):
#   .\scripts\dev-tunnel.ps1
# Leave the window open. Ctrl+C to stop all forwards at once.

$ErrorActionPreference = 'Stop'
$ns = 'edgeguardian'

$forwards = @(
    @{ svc = 'controller'; ports = '30443:8443'  },
    @{ svc = 'emqx';       ports = '31883:1883'  },
    @{ svc = 'emqx';       ports = '30883:8883'  }
)

$jobs = @()
try {
    foreach ($f in $forwards) {
        Write-Host "Starting port-forward svc/$($f.svc) $($f.ports)"
        $jobs += Start-Job -ScriptBlock {
            param($ns, $svc, $ports)
            kubectl -n $ns port-forward "svc/$svc" $ports
        } -ArgumentList $ns, $f.svc, $f.ports
    }

    Write-Host ""
    Write-Host "Forwards running. Ctrl+C to stop." -ForegroundColor Green
    Write-Host "  controller : http://localhost:30443"
    Write-Host "  emqx mqtt  : tcp://localhost:31883"
    Write-Host "  emqx mtls  : ssl://localhost:30883"
    Write-Host ""

    while ($true) {
        Start-Sleep -Seconds 5
        foreach ($j in $jobs) {
            if ($j.State -ne 'Running') {
                Write-Host "Port-forward job $($j.Id) died; check kubectl context" -ForegroundColor Red
            }
        }
    }
}
finally {
    Write-Host "Stopping port-forwards..."
    $jobs | ForEach-Object { Stop-Job $_ -ErrorAction SilentlyContinue; Remove-Job $_ -Force -ErrorAction SilentlyContinue }
}
