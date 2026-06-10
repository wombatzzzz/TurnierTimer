# Liest versionName aus app/build.gradle.kts und kopiert die Debug-APK in releases/
$gradleFile = "$PSScriptRoot\app\build.gradle.kts"
$versionName = (Select-String -Path $gradleFile -Pattern 'versionName\s*=\s*"(.+)"').Matches[0].Groups[1].Value

$releaseDir = "$PSScriptRoot\releases"
if (-not (Test-Path $releaseDir)) { New-Item -ItemType Directory -Path $releaseDir | Out-Null }

Write-Host "Baue APK fuer Version $versionName ..."
& "$PSScriptRoot\gradlew.bat" assembleDebug
if ($LASTEXITCODE -ne 0) {
    Write-Error "Build fehlgeschlagen."
    exit 1
}

$apkSrc = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkSrc)) {
    Write-Error "APK nicht gefunden: $apkSrc"
    exit 1
}

$dest = "$releaseDir\TurnierTimer-v$versionName.apk"
Copy-Item -Path $apkSrc -Destination $dest -Force
Write-Host "Fertig: $dest"
