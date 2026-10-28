param(
    [string]$BackendPython,
    [switch]$Install,
    [string]$Adb = "adb",
    [string]$Serial
)
$ErrorActionPreference = "Stop"
$frontendRoot = Split-Path $PSScriptRoot -Parent
$backendRoot = Join-Path (Split-Path $frontendRoot -Parent) "Backend"
if (-not $BackendPython) { $BackendPython = Join-Path $backendRoot "click/Scripts/python.exe" }
$manifestPath = Join-Path $backendRoot "instance/validation/environment.json"
$environment = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
Push-Location $backendRoot
try {
    & $BackendPython scripts/validation.py check
    if ($LASTEXITCODE -ne 0) { throw "Backend does not match the paired environment." }
}
finally { Pop-Location }
Push-Location $frontendRoot
try {
    & .\gradlew.bat :app:assembleValidation :app:assembleValidationAndroidTest --no-daemon --max-workers=2
    if ($LASTEXITCODE -ne 0) { throw "Validation APK build failed." }
    $apkPath = Join-Path $frontendRoot "app/build/outputs/apk/validation/app-validation.apk"
    $report = [ordered]@{
        application_id = "com.click.validation"
        api_base_url = $environment.api_base_url
        environment_id = $environment.environment_id
        fingerprint = $environment.fingerprint
        apk_sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        device_validation = "NOT_RUN"
        built_at_utc = [DateTime]::UtcNow.ToString("o")
    }
    $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path (Split-Path $apkPath -Parent) "validation-build.json") -Encoding UTF8
    if ($Install) {
        $deviceArgs = @()
        if ($Serial) { $deviceArgs = @("-s", $Serial) }
        & $Adb @deviceArgs get-state
        if ($LASTEXITCODE -ne 0) { throw "Connect and authorize one device, or specify -Serial." }
        $api = [Uri]$environment.api_base_url
        if ($api.Host -eq "127.0.0.1") {
            & $Adb @deviceArgs reverse "tcp:$($api.Port)" "tcp:$($api.Port)"
            if ($LASTEXITCODE -ne 0) { throw "USB API forwarding failed." }
        }
        & $Adb @deviceArgs install -r $apkPath
        if ($LASTEXITCODE -ne 0) { throw "Validation APK installation failed." }
        & $Adb @deviceArgs shell am start -n com.click.validation/com.click.MainActivity
        if ($LASTEXITCODE -ne 0) { throw "Validation app launch failed." }
    }
    Write-Output "Validation APK ready. Actual accessibility capture and completion verification remain NOT_RUN."
}
finally { Pop-Location }
