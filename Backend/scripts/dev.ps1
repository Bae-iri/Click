param(
    [string]$Python = "python",
    [int]$Port = 8000
)
$ErrorActionPreference = "Stop"
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & $Python scripts/validation.py prepare --env-file .env --profile jev --gov24 --port $Port
    if ($LASTEXITCODE -ne 0) { throw "Paired Jev environment preparation failed." }
    & $Python scripts/validation.py serve
    if ($LASTEXITCODE -ne 0) { throw "Server exited with an error." }
}
finally { Pop-Location }
