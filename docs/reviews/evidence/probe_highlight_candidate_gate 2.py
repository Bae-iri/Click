"""In-process proposal experiment; production selectors/graphs remain unchanged.

Seven developer-authored cases are regression evidence, not public/product gold.
The gate deliberately requires exact graph aliases for one Display-menu edge.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

import recheck_ui_highlighting as base
from ai_agent.runtime import matcher
from ai_agent.runtime.keywords import normalize_text


def evaluate():
    baseline = base.evaluate()
    original = matcher.eligible_nodes

    def gated(transition, nodes, graph):
        candidates = original(transition, nodes, graph)
        if transition.transition_id == "S1_TO_S2_DISPLAY_MENU":
            aliases = {normalize_text(word) for word in matcher.selector_keywords(transition, graph)}
            return tuple(node for node in candidates if normalize_text(node.label) in aliases)
        return candidates

    matcher.eligible_nodes = gated
    try:
        proposal = base.evaluate()
        positives = []
        for label in ("디스플레이", "화면", "Display"):
            request = base.AgentTurnRequest(
                request_id="synthetic-positive", session_id="synthetic-positive",
                raw_goal=base.RawGoalInput(source_text="글자 크기 키워줘", locale="ko-KR",
                    device_context=base.DeviceContext(platform="android", app_package="com.android.settings")),
                accessibility_tree=(base.node("title", "설정", 40, False),
                    base.node("display", label, 240), base.node("accessibility", "접근성", 340)),
                observation_id="synthetic-positive", captured_at_ms=1000,
                screen_bounds=(0, 0, 1080, 2400), active_window_id=7,
            )
            response = base.ClickAgentEngine(similarity_scorer=base.KeywordFallbackSimilarityScorer()).handle_turn(request)
            positives.append({"label": label, "expected_target": "display",
                              "actual_target": response.guide_command.target_node_id})
    finally:
        matcher.eligible_nodes = original
    passed = (proposal["incorrect_highlights"] == 0
              and all(item["actual_target"] == "accessibility" for item in proposal["cases"])
              and all(item["actual_target"] == item["expected_target"] for item in positives))
    return {"scope": "synthetic runtime experiment, not shipped fix or device validation",
            "production_code_modified": False, "device_tested": False,
            "similarity_provider": "KeywordFallbackSimilarityScorer; external embeddings not tested",
            "experiment_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "baseline": baseline, "proposal": proposal, "positive_cases": positives,
            "seven_case_experiment_passed": passed}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = evaluate()
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"baseline_wrong": report["baseline"]["incorrect_highlights"],
                      "proposal_wrong": report["proposal"]["incorrect_highlights"],
                      "positive_cases": report["positive_cases"],
                      "passed": report["seven_case_experiment_passed"]}, ensure_ascii=False))
    raise SystemExit(0 if report["seven_case_experiment_passed"] else 1)
