# Manually toggle notification access for FlowDroid — used to test watchdog rebind logic.

param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("allow", "deny")]
    [string]$Action
)

$listener = "com.flowdroid.debug/com.flowdroid.service.FlowDroidNotificationListenerService"

if ($Action -eq "allow") {
    Write-Host "==> Allowing notification access for $listener" -ForegroundColor Green
    adb shell cmd notification allow_listener $listener
} else {
    Write-Host "==> Denying notification access for $listener" -ForegroundColor Yellow
    adb shell cmd notification disallow_listener $listener
}

Write-Host ""
Write-Host "==> Current state:" -ForegroundColor Cyan
adb shell settings get secure enabled_notification_listeners
