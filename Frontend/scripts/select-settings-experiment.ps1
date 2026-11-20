param([ValidateSet('graph', 'llm')][string]$Arm = 'graph', [switch]$Manual)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$adb = Join-Path (Split-Path $repoRoot -Parent) '.android-sdk/platform-tools/adb.exe'
& $adb get-state
if ($LASTEXITCODE -ne 0) { throw 'Connect and authorize one USB device first' }
$original = (& $adb shell settings get secure enabled_accessibility_services).Trim()
$folder = Join-Path $repoRoot 'Backend/instance/settings-experiment'
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$saved = Join-Path $folder 'original-accessibility.txt'
if (-not (Test-Path -LiteralPath $saved)) { $original | Set-Content -Encoding UTF8 -LiteralPath $saved }
$others = @($original -split ':' | Where-Object { $_ -and $_ -ne 'null' -and $_ -notlike '*/com.click.service.accessibility.ClickAccessibilityService' })
$component = "com.click.settings$Arm/com.click.service.accessibility.ClickAccessibilityService"
$services = (@($others) + $component) -join ':'
& $adb shell settings put secure enabled_accessibility_services $services
if ($LASTEXITCODE -ne 0) { throw 'Could not select the experiment accessibility service' }
& $adb shell settings put secure accessibility_enabled 1
& $adb reverse tcp:8110 tcp:8110
& $adb shell am broadcast -n "com.click.settings$Arm/com.click.debug.Gov24GuideProbeReceiver" --ez experiment_automatic $(if ($Manual) { 'false' } else { 'true' })
& $adb shell am start -W -n "com.click.settings$Arm/com.click.HomeActivity"
Write-Output "EXPERIMENT_SELECTED: $Arm; one Click accessibility service; USB port 8110"
