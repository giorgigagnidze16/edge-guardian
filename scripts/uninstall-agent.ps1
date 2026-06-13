# EdgeGuardian agent uninstaller (Windows). Inverse of install.ps1.tmpl.
# Stops + removes the Windows service, then deletes the install and data dirs
# (binary, config, BoltDB, identity certs, logs). The device will re-enroll as a
# fresh identity on next install.
#
# Run from any PowerShell window; it self-elevates via UAC:
#   .\scripts\uninstall-agent.ps1

$ServiceName = 'EdgeGuardianAgent'
$InstallDir  = 'C:\Program Files\EdgeGuardian'
$ConfigDir   = 'C:\ProgramData\EdgeGuardian'

$principal = New-Object Security.Principal.WindowsPrincipal(
    [Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "Elevating (UAC prompt)..." -ForegroundColor Yellow
    Start-Process powershell.exe -Verb RunAs -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', "`"$PSCommandPath`""
    )
    return
}

$ErrorActionPreference = 'Stop'

Write-Host "EdgeGuardian Agent uninstaller" -ForegroundColor White
Write-Host ("-" * 40) -ForegroundColor DarkGray

$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($null -ne $svc) {
    Write-Host "Stopping service $ServiceName..." -ForegroundColor Cyan
    Stop-Service $ServiceName -Force -ErrorAction SilentlyContinue

    Write-Host "Deleting service $ServiceName..." -ForegroundColor Cyan
    $sc = Start-Process -FilePath sc.exe -ArgumentList 'delete', $ServiceName `
        -NoNewWindow -Wait -PassThru
    if ($sc.ExitCode -ne 0) {
        throw ("sc.exe delete failed with exit code {0}" -f $sc.ExitCode)
    }

    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) `
            -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 250
    }
    Write-Host "Service removed." -ForegroundColor Green
} else {
    Write-Host "Service $ServiceName not found (already removed)." -ForegroundColor DarkGray
}

foreach ($d in @($InstallDir, $ConfigDir)) {
    if (Test-Path -LiteralPath $d) {
        Write-Host ("Removing {0}" -f $d) -ForegroundColor Cyan
        Remove-Item -LiteralPath $d -Recurse -Force
    } else {
        Write-Host ("{0} not present." -f $d) -ForegroundColor DarkGray
    }
}

Write-Host ("-" * 40) -ForegroundColor DarkGray
Write-Host "Uninstall complete." -ForegroundColor Green
Write-Host "Press Enter to close..." -ForegroundColor DarkGray
Read-Host | Out-Null
