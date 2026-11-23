"""Post-hoc replay of a proposed early negative verdict on already collected responses."""
import argparse
from collections import Counter
from copy import deepcopy
import json
from pathlib import Path
from statistics import median
import time

from probe import payload


def contradicted(request, prepared):
    screen = request["screen"]
    if (prepared["reason"] != "outcome_literal" or request["planning_mode"] != "result_only"
            or screen["status"] != "public" or len(request["apps"]) != 1
            or screen["package"] != request["apps"][0]["package"]
            or not 0 <= time.time_ns() // 1_000_000 - screen["captured_at_ms"] < 15000):
        return False
    for clause in prepared["clauses"]:
        kind = clause["kind"]
        if kind not in {"on", "off", "selected"}:
            continue
        labels = {"".join(value.casefold().split()) for value in clause["labels"]}
        matches = [n for n in screen["nodes"] if "".join(n["label"].casefold().split()) in labels]
        if len(matches) != 1 or matches[0].get("source", "accessibility") != "accessibility":
            continue
        node = matches[0]
        if kind == "selected":
            if node["role"] == "tab" and node.get("selected") is False:
                return True
        elif node["role"] == "toggle":
            checked = node.get("checked")
            if isinstance(checked, bool) and checked != (kind == "on"):
                return True
    return False


def controls():
    """A new label and explicit omissions to exercise the decision boundary independently."""
    prepared = dict(reason="outcome_literal", clauses=[dict(kind="off", labels=["야간 모드"])])
    base = payload("weather", 0, 0, "near", None)
    switch = base["screen"]["nodes"][0]
    switch["label"] = "야간 모드"
    rows = []
    for name in ("opposite", "satisfied", "unknown", "duplicate", "ocr", "missing", "outside",
                 "stale", "other_app", "action_request", "model_contract"):
        request, source = deepcopy(base), deepcopy(prepared)
        screen = request["screen"]
        node = screen["nodes"][0]
        if name == "satisfied":
            node["checked"] = False
        elif name == "unknown":
            node.pop("checked")
        elif name == "duplicate":
            screen["nodes"].append(dict(node, id="duplicate"))
        elif name == "ocr":
            node["source"] = "ocr"
        elif name == "missing":
            screen["nodes"] = screen["nodes"][1:]
        elif name == "outside":
            screen.update(status="outside", nodes=[])
        elif name == "stale":
            screen["captured_at_ms"] -= 30000
        elif name == "other_app":
            screen["package"] = "org.example.other"
        elif name == "action_request":
            request["planning_mode"] = "goal_first"
        elif name == "model_contract":
            source["reason"] = "outcome_prepared"
        rows.append(dict(control=name, expected=name == "opposite", intercepted=contradicted(request, source)))
    return rows


def replay(rows):
    prepared = {}
    pairs = []
    for row in rows:
        key = (row["case"], row["form"], row["repeat"])
        if row["phase"] == "prepare":
            prepared[key] = row
            continue
        if "correct" not in row:
            continue
        request = payload(key[0], int(key[1] == "everyday"), key[2]-1, row["phase"], None)
        intercepted = contradicted(request, prepared[key])
        pairs.append(dict(case=key[0], form=key[1], repeat=key[2], phase=row["phase"],
                          intercepted=intercepted, original_correct=row["correct"],
                          replay_correct=(row["phase"] == "near") if intercepted else row["correct"],
                          original_calls=len(row["calls"]), original_api_ms=row["wall_ms"]))
    hit = [p for p in pairs if p["intercepted"]]
    return dict(conditions=dict(Counter(p["phase"] for p in hit)), decisions=len(pairs),
                original_correct=sum(p["original_correct"] for p in pairs),
                replay_correct=sum(p["replay_correct"] for p in pairs),
                original_calls_at_intercepts=sum(p["original_calls"] for p in hit),
                original_api_median_ms_at_intercepts=median(p["original_api_ms"] for p in hit) if hit else None,
                controls=controls(), pairs=pairs)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path)
    args = parser.parse_args()
    result = replay(json.loads(args.data.read_text())["rows"])
    assert all(r["expected"] == r["intercepted"] for r in result["controls"])
    print(json.dumps({key: value for key, value in result.items() if key != "pairs"},
                     indent=2, ensure_ascii=False))
