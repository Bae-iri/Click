param(
    [string]$BackendPython,
    [switch]$Install,
    [string]$Adb = "adb",
    [string]$Serial,
    [string]$Instance = "validation",
    [string]$GradleExecutable,
    [switch]$Offline
)
$ErrorActionPreference = "Stop"
$frontendRoot = Split-Path $PSScriptRoot -Parent
$backendRoot = Join-Path (Split-Path $frontendRoot -Parent) "Backend"
if (-not $BackendPython) { $BackendPython = Join-Path $backendRoot "click/Scripts/python.exe" }
$instancePath = [IO.Path]::GetFullPath((Join-Path $backendRoot "instance/$Instance"))
if (-not $instancePath.StartsWith(([IO.Path]::GetFullPath((Join-Path $backendRoot "instance")) + [IO.Path]::DirectorySeparatorChar))) {
    throw "Instance must be inside Backend/instance."
}
$manifestPath = Join-Path $instancePath "environment.json"
$environment = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
Push-Location $backendRoot
try {
    & $BackendPython scripts/validation.py --instance $instancePath check
    if ($LASTEXITCODE -ne 0) { throw "Backend does not match the paired environment." }
}
finally { Pop-Location }
Push-Location $frontendRoot
try {
    if (-not $GradleExecutable) { $GradleExecutable = Join-Path $frontendRoot "gradlew.bat" }
    $isolatedBuildRoot = Join-Path $instancePath "android-build"
    $validationKeystore = Join-Path $instancePath "validation-debug.keystore"
    if (-not (Test-Path -LiteralPath $validationKeystore)) {
        $existingDebugKeystore = Join-Path $env:USERPROFILE '.android/debug.keystore'
        if (Test-Path -LiteralPath $existingDebugKeystore) {
            Copy-Item -LiteralPath $existingDebugKeystore -Destination $validationKeystore
        } else {
            & keytool -genkeypair -keystore $validationKeystore -storepass android -alias AndroidDebugKey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
            if ($LASTEXITCODE -ne 0) { throw "Could not create the isolated validation signing key." }
        }
    }
    $applicationSuffix = if ($Instance -eq "coupang-device") { ".coupang.validation" } elseif ($Instance -eq "gov24") { ".gov24.validation" } else { ".validation" }
    $gradleOptions = @(':app:assembleValidation', ':app:assembleValidationAndroidTest', "-PCLICK_VALIDATION_MANIFEST=$manifestPath", "-PCLICK_VALIDATION_KEYSTORE=$validationKeystore", "-PCLICK_VALIDATION_APPLICATION_SUFFIX=$applicationSuffix",
        "-PCLICK_VALIDATION_BUILD_ROOT=$isolatedBuildRoot", '--project-cache-dir', (Join-Path $instancePath 'gradle-project-cache'),
        '--init-script', (Join-Path $PSScriptRoot 'validation-gradle.init.gradle'), '--no-daemon', '--max-workers=2')
    if ($Offline) { $gradleOptions += '--offline' }
    & $GradleExecutable @gradleOptions
    if ($LASTEXITCODE -ne 0) { throw "Validation APK build failed." }
    $apkPath = Join-Path $isolatedBuildRoot "app/outputs/apk/validation/app-validation.apk"
    $apkMetadata = Get-Content -LiteralPath (Join-Path (Split-Path $apkPath -Parent) "output-metadata.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    $applicationId = $apkMetadata.applicationId
    if (-not $applicationId -or -not $applicationId.EndsWith(".validation")) { throw "Expected a separate validation application." }
    $report = [ordered]@{
        application_id = $applicationId
        api_base_url = $environment.api_base_url
        environment_id = $environment.environment_id
        fingerprint = $environment.fingerprint
        apk_sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        device_validation = "NOT_RUN"
        built_at_utc = [DateTime]::UtcNow.ToString("o")
    }
    $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path (Split-Path $apkPath -Parent) "validation-build.json") -Encoding UTF8
    $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $instancePath "validation-build.json") -Encoding UTF8
    $artifactFolder = Join-Path $instancePath "artifacts"
    New-Item -ItemType Directory -Path $artifactFolder -Force | Out-Null
    $archivedApk = Join-Path $artifactFolder ($report.apk_sha256 + '.apk')
    if (-not (Test-Path -LiteralPath $archivedApk)) {
        Copy-Item -LiteralPath $apkPath -Destination $archivedApk
        Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $artifactFolder ($report.apk_sha256 + '-environment.json'))
    }
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
        $resolved = & $Adb @deviceArgs shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $applicationId
        $launcherComponent = $resolved | Where-Object { $_ -match '^[A-Za-z0-9_.]+/[A-Za-z0-9_.]+$' } | Select-Object -Last 1
        if (-not $launcherComponent) { throw "Validation launcher activity was not found." }
        $launchOutput = & $Adb @deviceArgs shell am start -n $launcherComponent
        Write-Output $launchOutput
        if ($LASTEXITCODE -ne 0 -or ($launchOutput -match 'Error:|Exception:')) { throw "Validation app launch failed." }
    }
    Write-Output "Validation APK ready. Actual accessibility capture and completion verification remain NOT_RUN."
}
finally { Pop-Location }
