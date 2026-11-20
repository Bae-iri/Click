param([string]$ApiBaseUrl = 'http://127.0.0.1:8110')
$ErrorActionPreference = 'Stop'
$frontendRoot = Split-Path $PSScriptRoot -Parent
$artifactRoot = Join-Path $frontendRoot 'app/build/outputs/settings-experiment'
New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null
Push-Location $frontendRoot
try {
    foreach ($arm in @('graph', 'llm')) {
        # Windows PowerShell treats redirected native stderr (including SDK warnings)
        # as errors. The Gradle exit code determines whether the build failed.
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest "-PCLICK_APPLICATION_ID=com.click.settings$arm" `
            "-PCLICK_SETTINGS_EXPERIMENT_ARM=$arm" "-PCLICK_API_BASE_URL=$ApiBaseUrl" --no-daemon --offline
        $buildExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($buildExit -ne 0) { throw "Settings $arm build failed" }
        Copy-Item -LiteralPath (Join-Path $frontendRoot 'app/build/outputs/apk/debug/app-debug.apk') `
            -Destination (Join-Path $artifactRoot "$arm.apk") -Force
        Copy-Item -LiteralPath (Join-Path $frontendRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk') `
            -Destination (Join-Path $artifactRoot "$arm-test.apk") -Force
    }
    Write-Output "Settings comparison APKs: $artifactRoot"
} finally { Pop-Location }
