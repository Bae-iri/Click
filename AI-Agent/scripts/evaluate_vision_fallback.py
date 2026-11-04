"""Deterministic AI1-1 integration benchmark using labeled OCR-element fixtures.

This does not pretend to measure ML Kit's pixel-to-text accuracy. That metric belongs to
the Android instrumentation test, which runs the real bundled model on generated images.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from statistics import mean
from time import perf_counter

PROJECT = Path(__file__).resolve().parents[1]
SRC = PROJECT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from ai_agent.runtime.engine import ClickAgentEngine
from ai_agent.runtime.similarity import KeywordFallbackSimilarityScorer
from ai_agent.runtime.types import (
    AgentTurnRequest,
    DeviceContext,
    RawGoalInput,
    VisionElement,
    VisionSnapshot,
)


CASES = (
    ("light_1080p_default", 1080, 2400, (24, 240, 1056, 320)),
    ("dark_1080p_default", 1080, 2400, (24, 240, 1056, 320)),
    ("light_720p_large_font", 720, 1600, (16, 150, 704, 230)),
    ("dark_1440p_small_font", 1440, 3200, (32, 320, 1408, 410)),
)


def request(case_name: str, width: int, height: int, bounds, *, vision: bool):
    return AgentTurnRequest(
        request_id=f"eval-{case_name}-{'vision' if vision else 'tree'}",
        session_id=f"eval-{case_name}-{'vision' if vision else 'tree'}",
        raw_goal=RawGoalInput(
            "increase font size",
            "en-US",
            DeviceContext(
                platform="android",
                app_package="com.android.settings",
                screen_bounds=(0, 0, width, height),
            ),
        ),
        accessibility_tree=(),
        vision_snapshot=VisionSnapshot(
            user_consented=True,
            consent_request_id=f"eval-{case_name}-vision",
            redaction_applied=True,
            provider="frontend_ocr",
            package_name="com.android.settings",
            screen_width=width,
            screen_height=height,
            processing_time_ms=120,
            elements=(
                VisionElement("title", "Settings", (24, 40, width - 24, 110),
                              "text", 0.96, False),
                VisionElement("display", "Display", bounds, "menu_item", 0.91, True),
            ),
        ) if vision else None,
    )


def evaluate() -> dict:
    rows = []
    for name, width, height, expected_bounds in CASES:
        started = perf_counter()
        vision = ClickAgentEngine(similarity_scorer=KeywordFallbackSimilarityScorer(), ui_selector=None).handle_turn(
            request(name, width, height, expected_bounds, vision=True))
        latency_ms = (perf_counter() - started) * 1000
        baseline = ClickAgentEngine(similarity_scorer=KeywordFallbackSimilarityScorer(), ui_selector=None).handle_turn(
            request(name, width, height, expected_bounds, vision=False))
        rows.append({
            "case": name,
            # Privacy normalization replaces provider IDs with their element index.
            "vision_target_found": vision.guide_command.target_node_id == "vision:ocr_1",
            "vision_bounds_correct": vision.guide_command.target_bounds == expected_bounds,
            "accessibility_only_target_found": baseline.guide_command.target_node_id is not None,
            "runtime_latency_ms": round(latency_ms, 3),
        })
    return {
        "schema_version": "ai1-vision-evaluation.v1",
        "fixture_type": "labeled_ocr_elements",
        "case_count": len(rows),
        "target_detection_success_rate": mean(row["vision_target_found"] for row in rows),
        "coordinate_accuracy_rate": mean(row["vision_bounds_correct"] for row in rows),
        "accessibility_only_success_rate": mean(
            row["accessibility_only_target_found"] for row in rows),
        "mean_ai1_runtime_latency_ms": round(mean(row["runtime_latency_ms"] for row in rows), 3),
        "pixel_to_text_accuracy": None,
        "pixel_to_text_accuracy_note": (
            "Run VisionOcrInstrumentedTest on an Android 11+ device; host fixtures start after OCR."
        ),
        "cases": rows,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = evaluate()
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    print(encoded, end="")


if __name__ == "__main__":
    main()
