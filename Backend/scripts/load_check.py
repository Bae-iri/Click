"""Disposable SQLite + fake AI benchmark; never targets a deployed server."""
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from time import perf_counter
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from app.main import create_app
from tests.helpers import register
from tests.test_backend_completion import ContractAgent
from tests.test_agent_sessions import payload


def main():
    with TemporaryDirectory(prefix="click-load-") as directory:
        app = create_app("sqlite+pysqlite:///" + (Path(directory) / "load.db").as_posix())
        app.state.turn_service.engine_factory = ContractAgent
        with TestClient(app) as client:
            register(client)
            def turn(index):
                value = payload("request-" + str(index), "session-" + str(index))
                start = perf_counter()
                response = client.post("/api/v1/agent/turn", json=value)
                return response.status_code, (perf_counter() - start) * 1000
            start = perf_counter()
            with ThreadPoolExecutor(max_workers=4) as pool:
                results = list(pool.map(turn, range(40)))
            durations = sorted(duration for _, duration in results)
            counts = {str(code): sum(status == code for status, _ in results) for code, _ in results}
            print(json.dumps({"scope": "local_sqlite_fake_ai", "requests": 40, "concurrency": 4,
                "statuses": counts, "elapsed_seconds": round(perf_counter()-start, 3),
                "p95_ms": round(durations[37], 2), "max_ms": round(max(durations), 2)}))
            if any(status != 200 for status, _ in results):
                raise SystemExit("Benchmark found unsuccessful requests")


if __name__ == "__main__":
    main()
