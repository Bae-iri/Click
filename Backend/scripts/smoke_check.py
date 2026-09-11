"""Verify migrations and a real local HTTP server using disposable data."""
from __future__ import annotations

import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import URLError
from urllib.request import Request, urlopen

BACKEND = Path(__file__).resolve().parents[1]


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="click-smoke-") as temporary:
        env = {**os.environ, "DATABASE_URL": f"sqlite+pysqlite:///{Path(temporary).as_posix()}/test.db",
               "GEMINI_API_KEY": ""}
        for args in (("upgrade", "head"), ("downgrade", "base"), ("upgrade", "head"), ("check",)):
            subprocess.run([sys.executable, "-m", "alembic", *args], cwd=BACKEND, env=env, check=True)
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            port = reserved.getsockname()[1]
        with (Path(temporary) / "server.log").open("w+", encoding="utf-8") as log:
            server = subprocess.Popen(
                [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(port)],
                cwd=BACKEND, env=env, stdout=log, stderr=log,
            )
            try:
                base = f"http://127.0.0.1:{port}"

                def fetch(path, payload=None):
                    request = Request(base + path, data=payload,
                                      headers={"Content-Type": "application/json"})
                    with urlopen(request, timeout=5) as response:
                        return response.status, json.load(response)

                deadline = time.monotonic() + 20
                while True:
                    if server.poll() is not None:
                        raise RuntimeError("Smoke server exited before startup")
                    try:
                        assert fetch("/health")[1] == {"status": "ok"}
                        break
                    except URLError:
                        if time.monotonic() >= deadline:
                            raise RuntimeError("Smoke server startup timed out")
                        time.sleep(0.2)
                assert fetch("/ready")[1] == {"status": "ready", "database": "ok"}
                assert "/api/v1/agent/turn" in fetch("/openapi.json")[1]["paths"]
                status, result = fetch("/api/v1/agent/turn", (BACKEND / "examples/agent-turn.json").read_bytes())
                assert status == 200 and result["session_id"] == "example-session-001"
                assert result["guide_command"]["target_node_id"] == "display"
                status, user = fetch("/api/v1/users", b'{"age_group":"unknown"}')
                assert status == 201
                assert fetch(f"/api/v1/users/{user['id']}")[1]["id"] == user["id"]
                print("PASS: migrations up/down/up + schema check; live health/ready/OpenAPI/turn/user APIs")
            finally:
                server.terminate()
                try:
                    server.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)


if __name__ == "__main__":
    main()
