param(
    [string]$Adb = "adb",
    [string]$Serial,
    [int]$Port = 8000
)
$ErrorActionPreference = "Stop"
if ($Port -lt 1 -or $Port -gt 65535) { throw "Invalid API port." }
$deviceArgs = @()
if ($Serial) { $deviceArgs = @("-s", $Serial) }
& $Adb @deviceArgs get-state
if ($LASTEXITCODE -ne 0) { throw "Connect one phone and authorize USB debugging, or specify -Serial." }
Invoke-RestMethod "http://127.0.0.1:$Port/ready" | Out-Null
& $Adb @deviceArgs reverse "tcp:$Port" "tcp:$Port"
if ($LASTEXITCODE -ne 0) { throw "USB API forwarding failed." }
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & .\gradlew.bat :app:assembleDebug "-PCLICK_API_BASE_URL=http://127.0.0.1:$Port"
    if ($LASTEXITCODE -ne 0) { throw "Android build failed." }
    & $Adb @deviceArgs install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw "APK installation failed." }
    & $Adb @deviceArgs shell am start -n com.click/.MainActivity
    if ($LASTEXITCODE -ne 0) { throw "App launch failed." }
}
finally { Pop-Location }
