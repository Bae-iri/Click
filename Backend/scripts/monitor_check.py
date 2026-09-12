"""Read-only probe; exit nonzero so a scheduler can trigger an operator alert."""
import json
import os
from urllib.request import Request, urlopen
from urllib.parse import urlsplit


def main():
    base = os.getenv("MONITOR_BASE_URL", "http://127.0.0.1:8000").rstrip("/")
    url = urlsplit(base)
    if url.scheme != "https" and not (url.scheme == "http" and url.hostname in {"localhost", "127.0.0.1", "::1"}):
        raise SystemExit("Use HTTPS except for loopback probes")
    result = {}
    for path in ("/health", "/ready"):
        try:
            with urlopen(base + path, timeout=5) as response:
                result[path] = response.status == 200
        except Exception:
            result[path] = False
    if os.getenv("OPS_TOKEN"):
        try:
            request = Request(base + "/ops/metrics", headers={"Authorization": "Bearer " + os.environ["OPS_TOKEN"]})
            with urlopen(request, timeout=5) as response:
                metrics = json.load(response)
            rate = metrics["server_errors"] / max(1, metrics["requests"])
            result["error_rate_ok"] = rate <= float(os.getenv("MONITOR_MAX_ERROR_RATE", "0.05"))
        except Exception:
            result["metrics_available"] = False
    print(json.dumps(result))
    if not all(result.values()):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
