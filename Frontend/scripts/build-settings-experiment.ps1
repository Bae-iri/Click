param([string]$ApiBaseUrl = 'http://127.0.0.1:8110')
$ErrorActionPreference = 'Stop'
$frontendRoot = Split-Path $PSScriptRoot -Parent
$artifactRoot = Join-Path $frontendRoot 'app/build/outputs/settings-experiment'
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null
Push-Location $frontendRoot
try {
    foreach ($arm in @('graph', 'llm')) {
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest "-PCLICK_APPLICATION_ID=com.click.settings$arm" `
            "-PCLICK_SETTINGS_EXPERIMENT_ARM=$arm" "-PCLICK_API_BASE_URL=$ApiBaseUrl" --no-daemon --offline
        if ($LASTEXITCODE -ne 0) { throw "Settings $arm build failed" }
        Copy-Item -LiteralPath (Join-Path $frontendRoot 'app/build/outputs/apk/debug/app-debug.apk') `
            -Destination (Join-Path $artifactRoot "$arm.apk") -Force
        Copy-Item -LiteralPath (Join-Path $frontendRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk') `
            -Destination (Join-Path $artifactRoot "$arm-test.apk") -Force
    }
    Write-Output "Settings comparison APKs: $artifactRoot"
} finally { Pop-Location }
