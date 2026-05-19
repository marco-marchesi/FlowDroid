# Quick health check — dump notification listener bind state and active services.

$pkg = "com.flowdroid.debug"

Write-Host "==> Notification listeners bound:" -ForegroundColor Cyan
adb shell dumpsys notification | Select-String -Pattern "flowdroid" -Context 0,1

Write-Host ""
Write-Host "==> Active services in $pkg" -ForegroundColor Cyan
adb shell dumpsys activity services $pkg | Select-String -Pattern "ServiceRecord|app=|started=|foreground="

Write-Host ""
Write-Host "==> Battery optimisation status:" -ForegroundColor Cyan
adb shell dumpsys deviceidle whitelist | Select-String -Pattern $pkg

Write-Host ""
Write-Host "==> Component enabled state:" -ForegroundColor Cyan
adb shell cmd package resolve-activity --components -p $pkg
