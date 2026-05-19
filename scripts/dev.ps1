# FlowDroid dev script — Windows PowerShell.
# Install the debug APK, launch the app, and stream filtered logcat.

param(
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$pkg = "com.flowdroid.debug"
$activity = "$pkg/com.flowdroid.MainActivity"

Write-Host "==> Building & installing debug APK..." -ForegroundColor Cyan
if ($Clean) {
    .\gradlew :app:clean :app:installDebug
} else {
    .\gradlew :app:installDebug
}

Write-Host "==> Launching $activity..." -ForegroundColor Cyan
adb shell am start -n $activity | Out-Null

Write-Host "==> Clearing logcat..." -ForegroundColor Cyan
adb logcat -c

Write-Host "==> Streaming filtered logcat (Ctrl-C to stop)..." -ForegroundColor Cyan
adb logcat *:S `
    FlowDroidApplication:V `
    FgService:V `
    Listener:V `
    Watchdog:V `
    NotifRepo:V `
    LogRepo:V `
    HealthRepo:V `
    OemBatteryHelper:V `
    CrashHandler:V
