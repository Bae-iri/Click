param()
$ErrorActionPreference = 'Stop'
$frontendRoot = Split-Path $PSScriptRoot -Parent
$repoRoot = Split-Path $frontendRoot -Parent
$adb = Join-Path (Split-Path $repoRoot -Parent) '.android-sdk/platform-tools/adb.exe'
$artifacts = Join-Path $frontendRoot 'app/build/outputs/settings-experiment'
$evidence = Join-Path $repoRoot 'Backend/instance/settings-experiment/device-readiness'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
foreach ($arm in @('graph', 'llm')) {
    foreach ($name in @("$arm.apk", "$arm-test.apk")) {
        $apk = Join-Path $artifacts $name
        if (-not (Test-Path -LiteralPath $apk)) { throw "Missing experiment artifact: $name" }
        & $adb install -r $apk
        if ($LASTEXITCODE -ne 0) { throw "Install failed: $name" }
    }
    & (Join-Path $PSScriptRoot 'select-settings-experiment.ps1') -Arm $arm
    $output = & $adb shell am instrument -w -r -e class com.click.data.remote.SettingsExperimentDeviceTest `
        -e settingsExperimentSmoke true "com.click.settings$arm.test/androidx.test.runner.AndroidJUnitRunner"
    $output | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $evidence "$arm-test.txt")
    if (($output -join "`n") -notmatch 'OK \(1 test\)' -or ($output -join "`n") -match 'FAILURES|INSTRUMENTATION_FAILED') {
        throw "DEVICE_NOT_READY: $arm; see $evidence"
    }
    $record = & $adb shell run-as "com.click.settings$arm" cat cache/settings-experiment-smoke.json
    if ($LASTEXITCODE -ne 0) { throw "Missing native evidence: $arm" }
    $record | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $evidence "$arm.json")
    $result = $record -join "`n" | ConvertFrom-Json
    if ($result.technical_status -ne 'CHANGED' -or [Math]::Abs($result.original_font_scale - $result.restored_font_scale) -gt 0.001) {
        throw "DEVICE_NOT_READY: $arm native result or value restoration failed"
    }
    Write-Output "DEVICE_VERIFIED: $arm; native change CHANGED; original value restored"
}
& (Join-Path $PSScriptRoot 'select-settings-experiment.ps1') -Arm graph
