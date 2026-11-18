param(
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [string]$VersionName = '1.0.3',
    [string]$SigningFile = '',
    [switch]$Unsigned
)
$ErrorActionPreference = 'Stop'
if ($VersionCode -lt 1) { throw 'VersionCode must be positive and greater than every uploaded Play version.' }
$frontendDirectory = Split-Path $PSScriptRoot -Parent
$gradleArgs = @(':app:bundleRelease', "-PCLICK_VERSION_CODE=$VersionCode",
    "-PCLICK_VERSION_NAME=$VersionName", '-PCLICK_CANDIDATE_DIAGNOSTICS=true')
if (-not $Unsigned) { $gradleArgs += '-PCLICK_REQUIRE_RELEASE_SIGNING=true' }
if ($SigningFile) { $gradleArgs += "-PCLICK_RELEASE_SIGNING_FILE=$SigningFile" }
Push-Location $frontendDirectory
try {
    & ./gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'AAB build failed.' }
    Write-Output 'Bundle: app/build/outputs/bundle/release/app-release.aab'
    if ($Unsigned) { Write-Output 'Unsigned preparation only: sign with the existing Play upload key before upload.' }
} finally { Pop-Location }
