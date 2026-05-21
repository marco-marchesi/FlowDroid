# FlowDroid manual install helper
#
# Usage (from the project root):
#   .\install.ps1               # Install on every connected known device
#   .\install.ps1 nokia         # Nokia 4.2 only
#   .\install.ps1 samsung       # Samsung A53 only
#   .\install.ps1 a53           # Synonym for samsung
#   .\install.ps1 both          # Same as no arg (all known devices)
#   .\install.ps1 -List         # Print connected devices and exit, no build

param(
    [Parameter(Position = 0)]
    [string]$Target = '',
    [switch]$List
)

$ErrorActionPreference = 'Stop'

# -- Configuration -------------------------------------------------------
$SERIALS = @{
    nokia   = '4T89571EA1950200601'   # Nokia 4.2  (Android 11)
    samsung = 'RZCT60L0QZJ'           # Samsung Galaxy A53 (Android 16)
}
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$ADB           = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$PROJECT_ROOT  = $PSScriptRoot
# -------------------------------------------------------------------------

function Write-Header([string]$msg) {
    Write-Host ""
    Write-Host "=== $msg ===" -ForegroundColor Cyan
}

function Get-ConnectedDevices {
    if (-not (Test-Path $ADB)) {
        Write-Host "ERROR: adb not found at: $ADB" -ForegroundColor Red
        Write-Host "       Edit the ADB path at the top of install.ps1." -ForegroundColor Yellow
        exit 1
    }
    $lines = & $ADB devices -l 2>&1
    $devices = @()
    foreach ($line in ($lines | Select-Object -Skip 1)) {
        $t = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($t)) { continue }
        $parts  = $t -split '\s+', 2
        $serial = $parts[0]
        $rest   = if ($parts.Count -gt 1) { $parts[1] } else { '' }
        $state  = ($rest -split '\s+')[0]
        $devices += [pscustomobject]@{ Serial = $serial; State = $state }
    }
    return $devices
}

function Resolve-Targets([string]$Target, $connected) {
    $wanted = switch ($Target.ToLower()) {
        'nokia'   { @('nokia') }
        'samsung' { @('samsung') }
        'a53'     { @('samsung') }
        default   { @('nokia', 'samsung') }   # '' or 'both'
    }

    $resolved = @()
    foreach ($name in $wanted) {
        $serial = $SERIALS[$name]
        $match  = $connected | Where-Object { $_.Serial -eq $serial -and $_.State -eq 'device' }
        if ($match) {
            $resolved += [pscustomobject]@{ Name = $name; Serial = $serial }
        } else {
            $offline = $connected | Where-Object { $_.Serial -eq $serial }
            if ($offline) {
                Write-Host "[skip] $name ($serial) - state='$($offline.State)'. Accept USB-debug prompt on the phone." -ForegroundColor Yellow
            } else {
                Write-Host "[skip] $name ($serial) - not connected." -ForegroundColor Yellow
            }
        }
    }
    return $resolved
}

function Install-OnDevice([string]$name, [string]$serial) {
    Write-Header "Installing on $name ($serial)"
    $env:ANDROID_SERIAL = $serial
    Push-Location $PROJECT_ROOT
    try {
        & .\gradlew.bat :app:installDebug --console=plain
        $rc = $LASTEXITCODE
        if ($rc -eq 0) {
            Write-Host "OK  $name installed successfully." -ForegroundColor Green
            return $true
        } else {
            Write-Host "FAIL $name - gradlew exited $rc." -ForegroundColor Red
            return $false
        }
    } finally {
        Pop-Location
        Remove-Item Env:\ANDROID_SERIAL -ErrorAction SilentlyContinue
    }
}

# -- Main -----------------------------------------------------------------
Write-Header "Connected devices"
$connected = Get-ConnectedDevices

if ($connected.Count -eq 0) {
    Write-Host "(none - plug a phone in and enable USB debugging)" -ForegroundColor Yellow
} else {
    foreach ($d in $connected) {
        $knownName = ($SERIALS.GetEnumerator() | Where-Object { $_.Value -eq $d.Serial } | Select-Object -First 1).Key
        $label = if ($knownName) { "$($d.Serial)  [$knownName]" } else { "$($d.Serial)  [unknown]" }
        $color = if ($d.State -eq 'device') { 'Green' } else { 'Yellow' }
        Write-Host "  $label  state=$($d.State)" -ForegroundColor $color
    }
}

if ($List) { exit 0 }
if ($connected.Count -eq 0) { exit 1 }

$targets = Resolve-Targets $Target $connected
if ($targets.Count -eq 0) {
    Write-Host "No target devices reachable. Nothing to install." -ForegroundColor Red
    exit 1
}

$results = @()
foreach ($t in $targets) {
    $ok = Install-OnDevice $t.Name $t.Serial
    $results += [pscustomobject]@{ Name = $t.Name; Ok = $ok }
}

Write-Header "Summary"
foreach ($r in $results) {
    if ($r.Ok) {
        Write-Host "  OK   $($r.Name)" -ForegroundColor Green
    } else {
        Write-Host "  FAIL $($r.Name)" -ForegroundColor Red
    }
}

$failCount = ($results | Where-Object { -not $_.Ok }).Count
exit $failCount
