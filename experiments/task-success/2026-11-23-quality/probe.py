"""Paired Korean requests at the real app-task API, using only invented public UI."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT / "Backend"), str(ROOT / "AI-Agent/src")]

from dotenv import dotenv_values
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.api.v1.endpoints.app_guide import build_router
from app.database import Base, build_engine, build_session_factory
from app.repositories.task_graphs import AmbiguousGraphError, NoCompatibleGraphError, TaskGraphRepository
from app.schemas.agent_turn import TypedGoalRequest
from app.services import app_task, app_task_outcome
from app.services.app_task_provider import ProviderError


# Nodes in each pair have stable identifiers; change only the selected state, checked
# state or page title. The scorer uses the independently assigned IDs and required flags.
def tab(label, selected):
    return dict(role="tab", label=label, clickable=True, selected=selected)


def heading(label):
    return dict(role="text", label=label, native_heading=True)


def switch(label, checked):
    return dict(role="toggle", label=label, clickable=True, checked=checked)


CASES = {
    "reader": dict(
        app="서재", goals=["서재에서 책갈피 탭을 열어줘", "읽던 곳을 표시해 둔 탭을 보여줘"],
        right=[tab("목차", False), tab("책갈피", True)],
        near=[tab("목차", True), tab("책갈피", False)], proof={1: {"selected": True}}),
    "transit": dict(
        app="버스", goals=["시간표 화면을 열어줘", "몇 시에 버스가 오는지 나오는 화면을 보여줘"],
        right=[heading("시간표"), dict(role="button", label="노선도", clickable=True)],
        near=[heading("노선도"), dict(role="button", label="시간표", clickable=True)],
        proof={0: {}}),
    "weather": dict(
        app="날씨", goals=["강수 알림을 꺼줘", "비가 올 때 받는 알림을 더는 받고 싶지 않아"],
        right=[switch("강수 알림", False), switch("폭염 알림", False)],
        near=[switch("강수 알림", True), switch("폭염 알림", False)],
        proof={0: {"checked": False}}),
    "podcast": dict(
        app="팟캐스트", goals=["보관함을 열고 자동 다운로드를 꺼줘", "저장한 방송 모음에 들어가서 새 방송이 저절로 내려받아지지 않게 해줘"],
        right=[heading("보관함"), switch("자동 다운로드", False)],
        near=[heading("보관함"), switch("자동 다운로드", True)], proof={0: {}, 1: {"checked": False}}),
}


def catalog():
    data = json.loads((ROOT / "Backend/app/task_graphs/goal_catalog.json").read_text())
    engine = build_engine("sqlite+pysqlite:///:memory:")
    groups = {}
    try:
        Base.metadata.create_all(engine)
        graphs = TaskGraphRepository(build_session_factory(engine))
        graphs.seed_mvp()
        for goal in data["goals"]:
            group = groups.setdefault(goal["target_app"], Counter())
            group[goal["execution_status"]] += 1
            try:
                typed = TypedGoalRequest(catalog_version=data["version"], goal_type=goal["goal_type"],
                                         target_app=goal["target_app"], slots=goal["slots"])
            except ValidationError:
                group["outside_typed_schema"] += 1
                continue
            try:
                graphs.select(typed)
            except AmbiguousGraphError:
                group["ambiguous"] += 1
            except NoCompatibleGraphError:
                group["no_graph"] += 1
            else:
                group["selected"] += 1
    finally:
        engine.dispose()
    return dict(version=data["version"], total=len(data["goals"]), by_app=groups)


def payload(case_id, form, repeat, phase, contract):
    case = CASES[case_id]
    task_id = f"study-{repeat}-{case_id}-{form}"
    package = "org.example." + case_id
    nodes = [dict(id=f"n{i}", resource=f"{package}:id/item_{i}",
                  bounds=[20, 100 + 100*i, 500, 180 + 100*i], **node)
             for i, node in enumerate(case.get(phase, []))]
    fingerprint = hashlib.sha256(json.dumps(nodes, sort_keys=True).encode()).hexdigest()
    return dict(request_id=f"{task_id}-{phase}", goal=case["goals"][form],
                screen=dict(package=package, status="public" if nodes else "empty", nodes=nodes,
                            observation_id=phase, captured_at_ms=time.time_ns() // 1_000_000,
                            fingerprint=fingerprint, width=1080, height=1920),
                apps=[dict(package=package, label=case["app"])],
                state=dict(task_id=task_id, outcome_goal=contract), prepare_outcome=phase == "prepare",
                planning_mode="goal_first" if phase == "prepare" else "result_only")


def proves_required_controls(response, screen, expected):
    if response.get("status") != "confirm_result" or response.get("action"):
        return False
    covered = set()
    checks = (response.get("completion") or {}).get("checks", [])
    for check in checks:
        if check["package"] != screen["package"] or not (check.get("label") or check.get("resource")):
            return False
        matched = [i for i, node in enumerate(screen["nodes"])
                   if all(node.get(key) == check[key] for key in ("role", "label", "resource")
                          if check.get(key))
                   and all(node.get(key) == check[key] for key in ("checked", "selected")
                           if check.get(key) is not None)]
        if len(matched) != 1:
            return False
        index = matched[0]
        if index in expected and all(check.get(k) is value for k, value in expected[index].items()):
            covered.add(index)
    return covered == set(expected)


def run(args):
    if args.provider_env:
        key = dotenv_values(args.provider_env).get("OPENROUTER_API_KEY")
        if key:
            os.environ["OPENROUTER_API_KEY"] = key
    if not os.environ.get("OPENROUTER_API_KEY"):
        raise SystemExit("Set OPENROUTER_API_KEY or provide --provider-env.")
    if args.output.exists():
        raise SystemExit("Choose a new output file to preserve the previous run.")
    os.environ.update(APP_TASK_PROVIDER="openrouter", APP_TASK_OPENROUTER_COMPACT="1",
                      APP_TASK_OPENROUTER_SHORT_MENU="0", APP_TASK_COMPLETION_TRIAGE="1")
    result = dict(source=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                  started_utc=datetime.now(timezone.utc).isoformat(), catalog=catalog(), rows=[])
    api = FastAPI()
    api.include_router(build_router(None, lambda: object(), app_task.AppTaskService()), prefix="/api/v1")
    original = app_task.generate
    calls = []

    def measured(model, token, body, **options):
        start = time.monotonic()
        record = dict(model=model, provider=options["provider"])
        calls.append(record)
        try:
            reply = original(model, token, body, **options)
            record.update(http=reply.status_code, call_state=reply.model_call_state)
            return reply
        except ProviderError as exc:
            record.update(error=exc.reason_code, call_state=exc.model_call_state)
            raise
        finally:
            record["wall_ms"] = round(1000 * (time.monotonic() - start), 2)

    with TestClient(api, raise_server_exceptions=False) as client, patch.object(app_task, "generate", measured), \
            patch.object(app_task_outcome, "generate", measured):
        for repeat in (0, 1):
            for index, (case_id, case) in enumerate(CASES.items()):
                first_form = (index + repeat) % 2
                for form in (first_form, 1-first_form):
                    contract = None
                    screens = ["right", "near"] if (repeat+form) % 2 == 0 else ["near", "right"]
                    for phase in ["prepare", *screens]:
                        row = dict(repeat=repeat+1, case=case_id,
                                   form=["literal", "everyday"][form], phase=phase)
                        result["rows"].append(row)
                        if phase != "prepare" and not contract:
                            row["status"] = "not_run_no_contract"
                            continue
                        body = payload(case_id, form, repeat, phase, contract)
                        calls.clear()
                        start = time.monotonic()
                        http = client.post("/api/v1/agent/app-guide/task", json=body,
                                           headers={"X-Click-App-Task-Budget-Ms": "10000"})
                        row.update(api_http=http.status_code, wall_ms=round(1000*(time.monotonic()-start), 2),
                                   calls=list(calls))
                        if http.status_code != 200:
                            row.update(status="http_error", reason=f"http_{http.status_code}")
                            if phase != "prepare":
                                row["correct"] = False
                            continue
                        response = http.json()
                        row.update(status=response["status"], reason=response["diagnostic"].get("reason_code"),
                                   action=(response.get("action") or {}).get("tool"))
                        if phase == "prepare":
                            contract = response.get("outcome_goal")
                            row["clauses"] = contract["clauses"] if contract else []
                        else:
                            row["checks"] = (response.get("completion") or {}).get("checks", [])
                            row["correct"] = (proves_required_controls(response, body["screen"], case["proof"])
                                              if phase == "right" else
                                              response["status"] == "needs_input" and response.get("action") is None)
                    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
                    print(f"block {repeat+1}, {case_id}, {row['form']}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--provider-env", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    run(parser.parse_args())
