param(
    [switch]$skipSign
)

$ErrorActionPreference = "Stop"
$projectDir = "F:\workspace\repo\Agora"
$wslDistro = "Arch"
$keystore = "C:/Users/newoether/.keystore/newoether"
$alias = "key0"
$password = "newo741596"
$apksigner = "D:\Program Files\Android\Android-SDK\build-tools\35.0.0\apksigner.bat"

$gradleFile = "$projectDir\app\build.gradle.kts"
$versionName = (Select-String -Path $gradleFile -Pattern 'versionName\s*=\s*"([^"]+)"').Matches.Groups[1].Value
$versionCode = (Select-String -Path $gradleFile -Pattern 'versionCode\s*=\s*(\d+)').Matches.Groups[1].Value

Write-Host "=== Agora F-Droid Build ===" -ForegroundColor Cyan
Write-Host "Version: $versionName ($versionCode)" -ForegroundColor Cyan

# ── Build in Arch WSL ──
Write-Host "`nBuilding in Arch WSL..." -ForegroundColor Yellow
$origSdk = Get-Content "$projectDir\local.properties" -Raw
$buildCmd = @'
set -e
cd /mnt/f/workspace/repo/Agora
echo "sdk.dir=/home/newoether/android-sdk" > local.properties
export ANDROID_HOME=/home/newoether/android-sdk
export ANDROID_SDK_ROOT=/home/newoether/android-sdk
export ANDROID_NDK_HOME=/home/newoether/android-sdk/ndk/28.2.13676358
export SOURCE_DATE_EPOCH=0
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
rm -rf app/build
./gradlew assembleRelease --no-daemon --stacktrace 2>&1
'@

wsl -d $wslDistro -- bash -c $buildCmd
if ($LASTEXITCODE -ne 0) { $origSdk | Set-Content "$projectDir\local.properties" -NoNewline; Write-Host "Build failed!" -ForegroundColor Red; exit 1 }

# Restore Windows SDK path (WSL build overwrites it)
$origSdk | Set-Content "$projectDir\local.properties" -NoNewline

$unsignedApk = "$projectDir\app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $unsignedApk)) {
    Write-Host "APK not found: $unsignedApk" -ForegroundColor Red
    exit 1
}

# ── Sign ──
if (-not $skipSign) {
    Write-Host "`nSigning..." -ForegroundColor Yellow
    $signedApk = "$projectDir\app\release\app-release.apk"
    & $apksigner sign --ks "$keystore" --ks-key-alias $alias --ks-pass pass:$password --key-pass pass:$password --out "$signedApk" "$unsignedApk"
    if ($LASTEXITCODE -ne 0) { Write-Host "Signing failed!" -ForegroundColor Red; exit 1 }
} else {
    Write-Host "`nSigning skipped" -ForegroundColor DarkGray
    $signedApk = $unsignedApk
}

$apkSize = [math]::Round((Get-Item $signedApk).Length / 1MB, 1)
Write-Host "`n=== Done: $signedApk ($apkSize MB) ===" -ForegroundColor Green
