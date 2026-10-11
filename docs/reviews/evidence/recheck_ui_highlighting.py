"""Reproduce semantic highlight errors without a device, network, or user data.

Run from any directory: python3 recheck_ui_highlighting.py [--output report.json]
Exit 1 means an incorrect highlight was reproduced; this is not a passing test.
Fixtures are synthetic counterexamples, not independently reviewed product gold.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "AI-Agent/src"))

from ai_agent.runtime import AccessibilityNode, ClickAgentEngine
from ai_agent.runtime.similarity import KeywordFallbackSimilarityScorer
from ai_agent.runtime.types import AgentTurnRequest, DeviceContext, RawGoalInput


def node(identifier, text, top, clickable=True):
    return AccessibilityNode(
        node_id=identifier, package_name="com.android.settings", window_id=7,
        class_name="android.widget.TextView", text=text,
        bounds_in_screen=(24, top, 1056, top + 80), clickable=clickable,
    )


def evaluate():
    cases = []
    # Accessibility is a graph-declared alternative. No Display menu is present.
    for label in ("잠금화면", "화면 자동 꺼짐 시간", "홈 화면", "화면 보호기"):
        request = AgentTurnRequest(
            request_id="synthetic-request", session_id="synthetic-session",
            raw_goal=RawGoalInput(source_text="글자 크기 키워줘", locale="ko-KR",
                device_context=DeviceContext(platform="android", app_package="com.android.settings")),
            accessibility_tree=(node("title", "설정", 40, False),
                node("unrelated-screen-menu", label, 240), node("accessibility", "접근성", 340)),
            observation_id="synthetic-observation", captured_at_ms=1000,
            screen_bounds=(0, 0, 1080, 2400), active_window_id=7,
        )
        response = ClickAgentEngine(similarity_scorer=KeywordFallbackSimilarityScorer()).handle_turn(request)
        guide = response.guide_command
        wrong = guide.target_node_id == "unrelated-screen-menu"
        cases.append({
            "source_kind": "synthetic_counterexample", "menu_label": label,
            "expected": "Do not highlight unrelated-screen-menu; abstain or use the declared alternative.",
            "state_id": response.current_state_id,
            "actual_target": guide.target_node_id, "actual_label": guide.target_label,
            "actual_bounds": guide.target_bounds, "actual_message": guide.message,
            "match_score": response.match_result.confidence if response.match_result else None,
            "incorrect_highlight": wrong,
        })
    files = ["AI-Agent/src/ai_agent/runtime/engine.py", "AI-Agent/src/ai_agent/runtime/keywords.py",
             "AI-Agent/src/ai_agent/runtime/matcher.py", "AI-Agent/src/ai_agent/runtime/targeting.py",
             "AI-Agent/src/ai_agent/runtime/tasks/mvp_01_font_size_increase.py"]
    return {"schema_version": "click-highlight-recheck.v1", "evidence_type": "synthetic_runtime_reproduction",
            "source_sha256": {f: hashlib.sha256((ROOT / f).read_bytes()).hexdigest() for f in files},
            "cases": cases, "incorrect_highlights": sum(c["incorrect_highlight"] for c in cases),
            "total_cases": len(cases), "device_rendering_tested": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = evaluate()
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered)
    raise SystemExit(1 if report["incorrect_highlights"] else 0)
