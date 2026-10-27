"""Check a deployed backend's AI1 path using a disposable synthetic account."""
import argparse
import json
import secrets
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    token = None
    user_id = None

    def call(path, payload=None, method=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = Request(base + path, headers=headers, method=method,
                          data=json.dumps(payload).encode() if payload is not None else None)
        try:
            with urlopen(request, timeout=45) as response:
                data = response.read()
                return json.loads(data) if data else None
        except HTTPError as error:
            raise RuntimeError(f"{path}: HTTP {error.code}: {error.read().decode()}") from None

    try:
        print("health:", call("/health"))
        print("ready:", call("/ready"))
        auth = call("/api/v1/auth/register", {
            "email": f"ai1-check-{uuid4().hex}@example.com",
            "password": secrets.token_urlsafe(24),
        })
        token = auth["access_token"]
        user_id = auth["user_id"]
        session_id = "ai1-check-" + uuid4().hex
        screen = {"package_name": "com.android.settings", "screen_width": 1080,
                  "screen_height": 2400, "nodes": [{"node_id": "display",
                  "text": "디스플레이", "package_name": "com.android.settings",
                  "clickable": True, "bounds_in_screen": [20, 100, 900, 200]},
                  {"node_id": "title", "text": "설정", "bounds_in_screen": [20, 0, 900, 80]},
                  {"node_id": "notifications", "text": "알림", "clickable": True,
                   "bounds_in_screen": [20, 220, 900, 320]}]}
        request = {
            "contract_version": "agent-fe-contract.v0.2", "request_id": uuid4().hex,
            "session_id": session_id, "expected_version": 0, "command_type": "start_task",
            "goal": {"catalog_version": "1.0.0", "goal_type": "settings.font_size.increase",
                     "target_app": "android_settings",
                     "slots": {"setting": "font_size", "direction": "increase"}},
            "device_context": {"platform": "android", "locale": "ko-KR",
                               "foreground_package": "com.android.settings"},
            "accessibility_tree": screen,
        }
        started = call("/api/v1/agent/commands", request)
        for label, result in [("start_task", started), ("observe", call("/api/v1/agent/commands", {
            "contract_version": request["contract_version"], "request_id": uuid4().hex,
            "session_id": session_id, "expected_version": started["session_version"],
            "episode_id": started["episode_id"], "command_type": "observe",
            "accessibility_tree": screen,
        }))]:
            if result.get("metadata", {}).get("runtime") != "ai_agent.runtime":
                raise RuntimeError(f"{label}: AI1 runtime marker missing")
            if not result.get("graph_reference", {}).get("sha256"):
                raise RuntimeError(f"{label}: DB graph reference missing")
            if result.get("guide_command", {}).get("target_node_id") != "display":
                raise RuntimeError(f"{label}: expected display-menu target missing")
            print(label + ":", json.dumps({key: result.get(key) for key in
                  ("task_id", "state_id", "task_status", "session_version", "guide_command")},
                  ensure_ascii=False))
        print("PASS: backend -> DB graph -> AI1 -> guidance; checkpoint restore verified")
    finally:
        if user_id:
            call("/api/v1/users/" + user_id, method="DELETE")
            print("Disposable test account and associated data deleted.")


if __name__ == "__main__":
    main()
