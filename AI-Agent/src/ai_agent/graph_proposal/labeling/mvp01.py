"""Conservative weak labels for replayable MVP-01 episodes.

Input is a reviewed, privacy-minimized JSONL fixture, not TraceLogger output.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

POLICY_VERSION = "mvp01-label-policy.v1"


def _label(outcome: str, reason: str, evidence_ids: list[str]) -> dict[str, Any]:
    return {
        "outcome_label": outcome,
        "label_status": "final",
        "label_method": "rule_based_weak_label",
        "label_policy_version": POLICY_VERSION,
        "reason": reason,
        "evidence_ids": evidence_ids,
    }


def label_episode(episode: dict[str, Any]) -> dict[str, Any]:
    """Label explicit attempts and final font-scale evidence separately."""
    if not isinstance(episode, dict):
        raise ValueError("episode must be an object")
    if episode.get("task_id") != "MVP-01":
        raise ValueError("Only MVP-01 is supported")
    episode_id = episode.get("episode_id")
    if not isinstance(episode_id, str) or not episode_id:
        raise ValueError("episode_id is required")
    steps = episode.get("steps")
    if not isinstance(steps, list):
        raise ValueError("steps must be a list")

    seen = set()
    labeled_steps = []
    for step in steps:
        step_id = step.get("step_id")
        if not isinstance(step_id, str) or not step_id or step_id in seen:
            raise ValueError(f"invalid or duplicate step_id in {episode_id}")
        seen.add(step_id)
        before = step.get("before_observation_id")
        after = step.get("after_observation_id")
        expected = step.get("expected_state")
        observed = step.get("observed_state")
        if step.get("action_observed") is True and before and after and before != after and expected and observed:
            outcome = "success" if expected == observed else "fail"
            reason = "expected_state_observed" if outcome == "success" else "different_state_observed"
            evidence = [before, after]
        else:
            outcome, reason, evidence = "unknown", "missing_linked_action_or_observation", [x for x in (before, after) if x]
        labeled_steps.append({"step_id": step_id, **_label(outcome, reason, evidence)})

    completion = episode.get("completion_evidence") or {}
    before = completion.get("before_observation_id")
    after = completion.get("after_observation_id")
    old = completion.get("before_font_scale")
    new = completion.get("after_font_scale")
    valid_values = (
        type(old) in (int, float) and type(new) in (int, float)
        and math.isfinite(old) and math.isfinite(new)
        and 0 < old <= 3 and 0 < new <= 3
    )
    if completion.get("source") == "accessibility_range" and before and after and before != after and valid_values:
        outcome = "success" if new > old else "fail"
        reason = "font_scale_increased" if outcome == "success" else "font_scale_not_increased"
        task_label = _label(outcome, reason, [before, after])
    elif episode.get("end_reason") in ("user_cancelled", "goal_changed"):
        task_label = _label("cancelled", episode["end_reason"], [])
    elif episode.get("end_reason") in ("consent_denied", "policy_blocked"):
        task_label = _label("not_applicable", episode["end_reason"], [])
    else:
        task_label = _label("unknown", "missing_direct_completion_evidence", [])
    return {"episode_id": episode_id, "task_id": "MVP-01", "steps": labeled_steps, "task": task_label}


def label_jsonl(source: Path, destination: Path) -> int:
    """Write one output record per input record; fail before writing on invalid data."""
    records = []
    ids = set()
    for line_number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            result = label_episode(json.loads(line))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValueError(f"line {line_number}: {exc}") from exc
        if result["episode_id"] in ids:
            raise ValueError(f"line {line_number}: duplicate episode_id")
        ids.add(result["episode_id"])
        records.append(result)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text("".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records), encoding="utf-8")
    return len(records)


def main() -> None:
    parser = argparse.ArgumentParser(description="Label MVP-01 episode JSONL")
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(f"labeled {label_jsonl(args.input, args.output)} episodes")


if __name__ == "__main__":
    main()
