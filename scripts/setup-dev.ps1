# Airblock minimal dev setup — NO Android Studio.
# Installs: Temurin JDK 17 + Android cmdline-tools/platform-tools + SDK 35 + Gradle 8.10.2
# Run from anywhere:  powershell -ExecutionPolicy Bypass -File .\scripts\setup-dev.ps1

$ErrorActionPreference = "Stop"
$sdkRoot = "$env:LOCALAPPDATA\Android\sdk"
$gradleRoot = "$env:LOCALAPPDATA\Gradle"
$projectDir = Split-Path -Parent $PSScriptRoot

# ---- 1. JDK 17 -------------------------------------------------------------
$java = Get-Command java -ErrorAction SilentlyContinue
if ($null -eq $java) {
    Write-Host ">> Installing Temurin JDK 17 via winget..." -ForegroundColor Cyan
    winget install --id EclipseAdoptium.Temurin.17.JDK --accept-package-agreements --accept-source-agreements --silent
    # Pick up the freshly installed JDK without reopening the shell
    $jdkDir = Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium" -Directory -Filter "jdk-17*" | Select-Object -First 1
    if ($null -ne $jdkDir) {
        $env:JAVA_HOME = $jdkDir.FullName
        $env:Path = "$($jdkDir.FullName)\bin;$env:Path"
    }
} else {
    Write-Host ">> Java found: $($java.Source)" -ForegroundColor Green
}
java -version

# ---- 2. Android SDK command-line tools + platform-tools (adb) --------------
New-Item -ItemType Directory -Force $sdkRoot | Out-Null

if (-not (Test-Path "$sdkRoot\platform-tools\adb.exe")) {
    Write-Host ">> Downloading platform-tools (adb)..." -ForegroundColor Cyan
    $pt = "$env:TEMP\platform-tools.zip"
    curl.exe -L -s -o $pt "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
    Expand-Archive $pt -DestinationPath $sdkRoot -Force
    Remove-Item $pt
}

if (-not (Test-Path "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat")) {
    Write-Host ">> Downloading Android cmdline-tools..." -ForegroundColor Cyan
    $ct = "$env:TEMP\cmdline-tools.zip"
    curl.exe -L -s -o $ct "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    $tmp = "$env:TEMP\cmdline-tools-extract"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive $ct -DestinationPath $tmp
    New-Item -ItemType Directory -Force "$sdkRoot\cmdline-tools" | Out-Null
    Move-Item "$tmp\cmdline-tools" "$sdkRoot\cmdline-tools\latest"
    Remove-Item $ct; Remove-Item -Recurse -Force $tmp
}

Write-Host ">> Accepting licenses + installing SDK 35..." -ForegroundColor Cyan
$sdkmanager = "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat"
$yes = ("y`n" * 30)
$yes | & $sdkmanager --sdk_root=$sdkRoot --licenses | Out-Null
& $sdkmanager --sdk_root=$sdkRoot "platforms;android-35" "build-tools;35.0.0"

# ---- 3. Gradle 8.10.2 ------------------------------------------------------
if (-not (Test-Path "$gradleRoot\gradle-8.10.2\bin\gradle.bat")) {
    Write-Host ">> Downloading Gradle 8.10.2..." -ForegroundColor Cyan
    $gz = "$env:TEMP\gradle.zip"
    curl.exe -L -s -o $gz "https://services.gradle.org/distributions/gradle-8.10.2-bin.zip"
    Expand-Archive $gz -DestinationPath $gradleRoot -Force
    Remove-Item $gz
}

# ---- 4. Point the project at the SDK + persist PATH ------------------------
$sdkForwardSlash = $sdkRoot -replace "\\", "/"
Set-Content -Path "$projectDir\local.properties" -Value "sdk.dir=$sdkForwardSlash" -Encoding ascii

$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
foreach ($p in @("$sdkRoot\platform-tools", "$gradleRoot\gradle-8.10.2\bin")) {
    if ($userPath -notlike "*$p*") { $userPath = "$userPath;$p" }
}
[Environment]::SetEnvironmentVariable("Path", $userPath, "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $sdkRoot, "User")

Write-Host ""
Write-Host "DONE. Open a NEW terminal so PATH changes apply, then:" -ForegroundColor Green
Write-Host "  cd `"$projectDir`""
Write-Host "  gradle assembleDebug        # first build downloads dependencies (a few min)"
Write-Host "  .\scripts\deploy.ps1        # build + install on the paired phone"
