param([switch]$Install, [ValidateSet('graph', 'llm')][string]$Arm = 'graph')
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path $PSScriptRoot -Parent
$repoRoot = Split-Path $backendRoot -Parent
$workspaceRoot = Split-Path $repoRoot -Parent
$experimentRoot = Join-Path $backendRoot 'instance/settings-experiment'
$python = (Get-Command python -ErrorAction Stop).Source
$paths = @($backendRoot, (Join-Path $repoRoot 'AI-Agent/src'))
$localDeps = Join-Path $workspaceRoot '.backend-test-deps'
if (Test-Path -LiteralPath $localDeps) { $paths += $localDeps }
if ($env:PYTHONPATH) { $paths += $env:PYTHONPATH }
$env:PYTHONPATH = $paths -join [IO.Path]::PathSeparator
Push-Location $backendRoot
try {
    & $python -X utf8 scripts/prepare_settings_experiment.py
    if ($LASTEXITCODE -ne 0) { throw 'Experiment environment preparation failed' }
    $baseUrl = 'http://127.0.0.1:8110'
    $ready = $false
    try { $ready = (Invoke-RestMethod "$baseUrl/ready" -TimeoutSec 2).status -eq 'ready' } catch {}
    if (-not $ready) {
        $process = Start-Process -FilePath $python -ArgumentList @('-X', 'utf8', '-m', 'uvicorn',
            'app.main:app', '--env-file', 'instance/settings-experiment/.env', '--host', '127.0.0.1', '--port', '8110') `
            -WorkingDirectory $backendRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $experimentRoot 'server.stdout.log') `
            -RedirectStandardError (Join-Path $experimentRoot 'server.stderr.log')
        $process.Id | Set-Content -LiteralPath (Join-Path $experimentRoot 'server.pid')
        for ($attempt = 0; $attempt -lt 30; $attempt++) {
            if ($process.HasExited) { throw 'Server exited; inspect instance/settings-experiment/server.stderr.log' }
            try { $ready = (Invoke-RestMethod "$baseUrl/ready" -TimeoutSec 1).status -eq 'ready' } catch {}
            if ($ready) { break }
            Start-Sleep -Milliseconds 300
        }
        if (-not $ready) { throw 'Experiment server did not become ready' }
    }
    # A healthy unrelated backend must not silently accept the experiment clients.
    $schema = Invoke-RestMethod "$baseUrl/openapi.json" -TimeoutSec 3
    if (-not $schema.components.schemas.DeviceCapabilities.properties.settings_experiment_arm) {
        throw 'Port 8110 serves a backend without the Settings experiment capability'
    }
    $version = Invoke-RestMethod "$baseUrl/version" -TimeoutSec 5
    if ($version.runtime_configuration.provider -ne 'jev' -or -not $version.runtime_configuration.settings_planner_model) {
        throw 'Port 8110 serves an old or disabled experiment backend. Stop its server process and run this command again.'
    }
    Write-Output 'BACKEND_READY: http://127.0.0.1:8110'
    if ($Install) {
        $adb = Join-Path $workspaceRoot '.android-sdk/platform-tools/adb.exe'
        if (-not (Test-Path -LiteralPath $adb)) { throw 'adb.exe is missing' }
        & $adb get-state
        if ($LASTEXITCODE -ne 0) { throw 'Connect one USB debugging device and authorize this PC first' }
        & $adb reverse tcp:8110 tcp:8110
        if ($LASTEXITCODE -ne 0) { throw 'ADB reverse failed' }
        foreach ($apkArm in @('graph', 'llm')) {
            $apk = Join-Path $repoRoot "Frontend/app/build/outputs/settings-experiment/$apkArm.apk"
            if (-not (Test-Path -LiteralPath $apk)) { throw "Missing APK: $apk" }
            & $adb install -r $apk
            if ($LASTEXITCODE -ne 0) { throw "Install failed: $apkArm" }
        }
        & (Join-Path $repoRoot 'Frontend/scripts/select-settings-experiment.ps1') -Arm $Arm
    }
    $plannerOutput = & $python -X utf8 scripts/check_settings_planner.py
    $plannerExit = $LASTEXITCODE
    $plannerOutput | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $experimentRoot 'planner-preflight.json')
    Write-Output $plannerOutput
    if ($plannerExit -ne 0) {
        Write-Warning 'LLM_NOT_READY: the configured Settings planner did not return a valid plan.'
        exit 1
    }
    $checkedModel = ($plannerOutput | ConvertFrom-Json).diagnostic.model
    if ($version.runtime_configuration.settings_planner_model -ne $checkedModel) {
        throw 'The server on port 8110 uses a different planner than the experiment environment. Restart that server.'
    }
    Write-Output 'PLANNER_READY: valid live plan. Device comparison still requires both app trials.'
} finally { Pop-Location }
