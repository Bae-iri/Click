from threading import Lock


class RequestMetrics:
    def __init__(self):
        self.lock = Lock()
        self.requests = self.errors = self.duration_ms = 0
        self.statuses = {}

    def record(self, status, duration_ms):
        with self.lock:
            self.requests += 1
            self.errors += int(status >= 500)
            self.duration_ms += duration_ms
            self.statuses[str(status)] = self.statuses.get(str(status), 0) + 1

    def snapshot(self):
        with self.lock:
            return {"requests": self.requests, "server_errors": self.errors,
                "mean_duration_ms": self.duration_ms / max(1, self.requests), "status_counts": dict(self.statuses)}
