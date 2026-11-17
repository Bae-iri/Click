"""Compare synthetic screen payloads locally. No key, network or real UI is used."""
import argparse
import json
import os
from pathlib import Path
import statistics
import sys

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / "AI-Agent" / "src"))

import httpx
from app.services.screen_jev import JevScreenResolver
from app.task_graphs import approved_graphs
from ai_agent.runtime.types import NormalizedNode, TypedGoal
from ai_agent.runtime.keywords import normalize_text


class PayloadProbe(JevScreenResolver):
    @staticmethod
    def configuration():
        return "unused-synthetic-key", "jev-1.14.0", .8


def cases():
    graphs = approved_graphs()
    for setting in ("font_size", "brightness", "volume"):
        graph = graphs[f"settings.{setting}.increase"]
        slider = next(t for t in graph.transitions if t.target_role == "slider")
        package = (slider.selector.package_candidates or graph.states[slider.from_state].package_candidates)[0]
        yield setting + "_control", graph, (NormalizedNode("control", normalize_text(slider.target_keywords[0]),
            "slider", bounds=(20, 300, 1000, 380), actionable=True, package_name=package,
            value=3, range_min=0, range_max=10),)
        entry = next(t for t in graph.transitions if t.from_state == "S1_SETTINGS_HOME")
        yield setting + "_settings_home", graph, (
            NormalizedNode("title", "설정", "text", bounds=(20, 20, 1000, 100),
                package_name="com.android.settings", structure_role="screen_text"),
            NormalizedNode("menu", normalize_text(entry.target_keywords[0]), "menu_item",
                bounds=(20, 200, 1000, 280), actionable=True, clickable=True,
                package_name="com.android.settings", structure_role="control"))
    yield "coupang_navigation", graphs["coupang.order.list"], (
        NormalizedNode("title", "홈", "text", bounds=(20, 20, 1000, 100), window_id=1,
            package_name="com.coupang.mobile", structure_role="screen_text"),
        NormalizedNode("menu", "마이쿠팡", "menu_item", bounds=(20, 200, 1000, 280), window_id=1,
            actionable=True, clickable=True, package_name="com.coupang.mobile", structure_role="control"))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--iterations", type=int, choices=range(1, 101), default=20)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    prior = os.environ.get("JEV_SCREEN_JUDGMENT_MODE")
    report = {"source": "synthetic_payload_only", "actual_provider_calls": 0,
        "measures_server_latency": False, "cases": []}
    try:
        for name, graph, nodes in cases():
            definition = graph.definition
            goal = TypedGoal(definition.goal_type, definition.target_app, definition.task_type,
                definition.required_slots, "", 1, task_id=definition.task_id,
                is_supported=True, required_slots_filled=True)
            item = {"case": name}
            for mode in ("legacy", "atomic"):
                os.environ["JEV_SCREEN_JUDGMENT_MODE"] = mode
                times, sizes, calls = [], [], []
                def capture(payload, **kwargs):
                    calls.append(len(payload["questions"]))
                    sizes.append(len(json.dumps(payload, ensure_ascii=False, allow_nan=False).encode()))
                    return httpx.Response(503)  # Stub; no external call or model result.
                resolver = PayloadProbe(capture)
                for _ in range(args.iterations):
                    resolver.resolve(nodes=nodes, graph=graph, goal=goal, screen_bounds=(0, 0, 1080, 2400))
                    if resolver.last_diagnostics.get("called") is not True:
                        raise ValueError("Synthetic case did not reach the request boundary: " + name)
                    if "filter_time_ms" in resolver.last_diagnostics:
                        times.append(resolver.last_diagnostics["filter_time_ms"])
                item[mode] = {"payload_bytes": sizes[0], "questions_per_request": calls[0],
                    "stub_requests_per_observation": len(calls) / args.iterations}
                if times:
                    item[mode]["local_preparation_median_ms"] = round(statistics.median(times), 3)
            item["payload_change_percent"] = round(100 * (item["atomic"]["payload_bytes"] / item["legacy"]["payload_bytes"] - 1), 1)
            report["cases"].append(item)
    finally:
        if prior is None:
            os.environ.pop("JEV_SCREEN_JUDGMENT_MODE", None)
        else:
            os.environ["JEV_SCREEN_JUDGMENT_MODE"] = prior
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
