"""Verify migrations and a real local HTTP server using disposable data."""
from __future__ import annotations

import json
import os
from pathlib import Path
import socket
import site
import subprocess
import sys
import tempfile
import time
from urllib.error import URLError
from urllib.request import Request, urlopen

BACKEND = Path(__file__).resolve().parents[1]


def stop_server(server):
    server.terminate()
    try:
        server.wait(timeout=10)
    except subprocess.TimeoutExpired:
        server.kill()
        server.wait(timeout=5)


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
            def start_server():
                executable = sys.executable
                server_env = env
                if os.name == "nt":
                    # Start the interpreter directly, not the venv redirector.
                    # Otherwise terminating the launcher can orphan uvicorn.
                    executable = getattr(sys, "_base_executable", sys.executable)
                    server_env = {**env, "PYTHONPATH": os.pathsep.join([
                        *site.getsitepackages(), *filter(None, [env.get("PYTHONPATH")])])}
                return subprocess.Popen(
                    [executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(port),
                     "--no-access-log", "--no-proxy-headers", "--log-config", "logging.json"],
                    cwd=BACKEND, env=server_env, stdout=log, stderr=log)
            server = start_server()
            try:
                base = f"http://127.0.0.1:{port}"
                token = None

                def fetch(path, payload=None):
                    headers = {"Content-Type": "application/json"}
                    if token:
                        headers["Authorization"] = f"Bearer {token}"
                    request = Request(base + path, data=payload,
                                      headers=headers)
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
                status, auth = fetch("/api/v1/auth/register", json.dumps({
                    "email": "smoke@example.com", "password": "smoke-password-1234",
                }).encode())
                assert status == 201
                token = auth["access_token"]
                status, result = fetch("/api/v1/agent/turn", (BACKEND / "examples/agent-turn.json").read_bytes())
                assert status == 200 and result["session_id"] == "example-session-001"
                assert result["guide_command"]["target_node_id"] == "display"
                status, user = fetch("/api/v1/users/me")
                assert status == 200
                assert fetch(f"/api/v1/users/{user['id']}")[1]["id"] == user["id"]
                stop_server(server)
                server = start_server()
                deadline = time.monotonic() + 20
                while True:
                    if server.poll() is not None:
                        raise RuntimeError("Restarted server exited")
                    try:
                        fetch("/health")
                        break
                    except URLError:
                        if time.monotonic() >= deadline:
                            raise RuntimeError("Restart timed out")
                        time.sleep(0.2)
                state_path = "/api/v1/agent/sessions/example-session-001"
                assert fetch(state_path)[1]["version"] == 1
                sample = (BACKEND / "examples/agent-turn.json").read_bytes()
                assert fetch("/api/v1/agent/turn", sample)[1] == result
                next_turn = json.loads(sample)
                next_turn.pop("raw_goal")
                next_turn.update(request_id="example-turn-002", expected_version=1)
                assert fetch("/api/v1/agent/turn", json.dumps(next_turn).encode())[1]["session_version"] == 2
                assert fetch(state_path + "/cancel", b'{"expected_version":2}')[1]["status"] == "CANCELLED"
                log.flush()
                log.seek(0)
                output = log.read()
                assert "trace_id=" in output
                assert "smoke-password" not in output and token not in output
                print("PASS: migrations; live auth/turn; process restart/replay/resume/cancel; safe logs")
            finally:
                stop_server(server)


if __name__ == "__main__":
    main()
