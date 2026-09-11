param(
    [string]$Python = "python",
    [int]$Port = 8000
)
$ErrorActionPreference = "Stop"
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    if (-not $env:DATABASE_URL) {
        $env:DATABASE_URL = "sqlite+pysqlite:///./click-dev.db"
    }
    & $Python -m alembic upgrade head
    if ($LASTEXITCODE -ne 0) { throw "Database migration failed." }
    & $Python -m uvicorn app.main:app --host 127.0.0.1 --port $Port --reload --no-access-log --no-proxy-headers --log-config logging.json
    if ($LASTEXITCODE -ne 0) { throw "Server exited with an error." }
}
finally { Pop-Location }
