# Build + install Airblock on the connected phone, then tail its logs.
# Usage:  .\scripts\deploy.ps1            (build, install)
#         .\scripts\deploy.ps1 -Logs      (build, install, then live logcat)
param([switch]$Logs)

$ErrorActionPreference = "Stop"
$projectDir = Split-Path -Parent $PSScriptRoot
Set-Location $projectDir

gradle assembleDebug
if (-not $?) { exit 1 }

$apk = "app\build\outputs\apk\debug\app-debug.apk"
Write-Host ">> Installing $apk ..." -ForegroundColor Cyan
adb install -r $apk
if (-not $?) {
    Write-Host "If this failed with INSTALL_FAILED_UPDATE_INCOMPATIBLE, run: adb uninstall com.sam.airblock" -ForegroundColor Yellow
    exit 1
}
Write-Host ">> Installed." -ForegroundColor Green

if ($Logs) {
    Write-Host ">> Live logs (Ctrl+C to stop):" -ForegroundColor Cyan
    adb logcat -s Airblock
}
