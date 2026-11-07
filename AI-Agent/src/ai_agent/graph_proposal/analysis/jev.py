"""Assess model choices separately from action execution, using explicit denominators."""
from collections import Counter, defaultdict
from copy import deepcopy

from ai_agent.graph_proposal.contracts import fingerprint, hash_value, identifier, validate_event

POLICY = "jev-assessment.v1"
REVIEW_SCHEMA = "ai2-jev-reviews.v1"
SCORES = ("raw_confidence", "normalized_probability", "confidence")
SPLITS = {"train", "validation", "holdout"}
THRESHOLDS = (0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95)


def rate(numerator, denominator):
    return numerator / denominator if denominator else None


def validate_reviews(document):
    if (not isinstance(document, dict) or set(document) != {"schema_version", "revision", "records"}
            or document["schema_version"] != REVIEW_SCHEMA or not isinstance(document["records"], list)):
        raise ValueError("Invalid Jev review manifest")
    identifier(document["revision"])
    seen, groups = set(), {}
    for review in document["records"]:
        fields = {"event_id", "decision_hash", "screen_hash", "target_hash", "label", "reviewer",
                  "review_revision", "evidence_refs", "split", "group_id"}
        if not isinstance(review, dict) or set(review) != fields:
            raise ValueError("Invalid Jev review record")
        for key in ("event_id", "reviewer", "review_revision", "group_id"):
            identifier(review[key], key)
        for key in ("decision_hash", "screen_hash", "target_hash"):
            hash_value(review[key])
        if review["decision_hash"] in seen:
            raise ValueError("Duplicate reviewed decision")
        seen.add(review["decision_hash"])
        if review["label"] not in {"correct", "incorrect", "unknown"} or review["split"] not in SPLITS:
            raise ValueError("Invalid Jev reviewed label/split")
        if groups.setdefault(review["group_id"], review["split"]) != review["split"]:
            raise ValueError("Review group leakage across splits")
        if not isinstance(review["evidence_refs"], list) or not review["evidence_refs"]:
            raise ValueError("Independent target reviews require evidence references")
        for reference in review["evidence_refs"]:
            identifier(reference, "evidence reference")
    return deepcopy(document)


def _review_metrics(records):
    labeled = [r for r in records if r.get("review_label") in {"correct", "incorrect"}]
    correct = sum(r["review_label"] == "correct" for r in labeled)
    output = {"reviewed_targets": len(labeled), "correct_targets": correct,
              "target_accuracy": rate(correct, len(labeled)), "scores": {}, "thresholds": []}
    for score in SCORES:
        scored = [r for r in labeled if score in r["decision"]]
        bins = []
        for lower, upper in ((0, .5), (.5, .8), (.8, .9), (.9, 1)):
            bucket = [r for r in scored if lower <= r["decision"][score]
                      and (r["decision"][score] < upper or upper == 1)]
            bins.append({"lower": lower, "upper": upper, "samples": len(bucket),
                "mean_score": rate(sum(r["decision"][score] for r in bucket), len(bucket)),
                "target_accuracy": rate(sum(r["review_label"] == "correct" for r in bucket), len(bucket))})
        output["scores"][score] = {"samples": len(scored), "bins": bins,
            "brier_score": rate(sum((r["decision"][score] - int(r["review_label"] == "correct")) ** 2
                                    for r in scored), len(scored))}
    paired = [r for r in labeled if all(key in r["decision"] for key in ("raw_confidence", "normalized_probability"))]
    for threshold in THRESHOLDS:
        accepted = [r for r in paired if min(r["decision"]["raw_confidence"], r["decision"]["normalized_probability"]) >= threshold]
        wrong = sum(r["review_label"] == "incorrect" for r in accepted)
        held_correct = sum(r["review_label"] == "correct" and min(r["decision"]["raw_confidence"],
                           r["decision"]["normalized_probability"]) < threshold for r in paired)
        output["thresholds"].append({"threshold": threshold, "reviewed_candidates": len(paired),
            "accepted": len(accepted), "wrong_accepted": wrong, "wrong_accepted_rate": rate(wrong, len(accepted)),
            "correct_held": held_correct, "coverage": rate(len(accepted), len(paired))})
    return output


def _execution_metrics(records):
    outcomes = [outcome for record in records for outcome in record["outcomes"]]
    success = sum(outcome["label"] == "verified_success" for outcome in outcomes)
    durations = [outcome["duration_ms"] for outcome in outcomes if outcome.get("duration_ms") is not None]
    return {"verified_attempts": len(outcomes), "successes": success, "failures": len(outcomes) - success,
            "success_rate": rate(success, len(outcomes)),
            "mean_duration_ms": rate(sum(durations), len(durations)),
            "decisions_with_verified_outcome": sum(bool(r["outcomes"]) for r in records),
            "selected_without_verified_outcome": sum(r["decision"]["status"] == "selected" and not r["outcomes"] for r in records)}


def analyze_jev(events, episodes, *, now, reviews=None):
    """Scores are not ground truth. Unexecuted holds never enter execution failure rates."""
    observations, decisions, seen_events = Counter(), {}, set()
    for raw in events:
        event = validate_event(raw)
        if event["expires_at"] <= now or event["event_id"] in seen_events:
            continue
        seen_events.add(event["event_id"])
        decision = event["runtime"].get("jev_decision")
        if not decision:
            observations["without_jev_evidence"] += 1
            continue
        observations["jev_observations"] += 1
        observations["retained_guides"] += int(decision.get("retained", False))
        observations["paused_observations"] += int(decision.get("paused", False))
        if not decision["called"]:
            continue
        key = decision["decision_hash"]
        if key in decisions:
            raise ValueError("Decision identity reused across provider calls")
        decisions[key] = {"event_id": event["event_id"], "session_key": event["session_key"],
            "episode_id": event["episode_id"], "task_id": event["task_id"], "graph_hash": event["graph_hash"],
            "state_id": event["runtime"].get("state_id"), "source_kind": event["source_kind"],
            "device_cohort": event.get("device_cohort", "unknown:android-unknown"),
            "decision": deepcopy(decision), "outcomes": []}
    observations["provider_calls"] = len(decisions)
    reassigned, seen_attempts = 0, set()
    for episode in episodes:
        if not episode["complete_evidence"]:
            continue
        for step in episode["steps"]:
            record = decisions.get(step.get("decision_hash"))
            attempt = (episode["session_key"], step.get("attempt_hash"))
            if (not record or step["label"] not in {"verified_success", "verified_failure"}
                    or not step.get("attempt_hash") or attempt in seen_attempts):
                continue
            if record["session_key"] != episode["session_key"] or record["episode_id"] != episode["episode_id"]:
                raise ValueError("Jev outcome crossed a session/episode boundary")
            seen_attempts.add(attempt)
            if step.get("model_target_executed") is not True:
                reassigned += 1
                continue
            record["outcomes"].append({"event_id": step["event_id"], "attempt_hash": step["attempt_hash"],
                "label": step["label"], "duration_ms": step.get("duration_ms")})
    document = validate_reviews(reviews) if reviews is not None else None
    session_splits = {}
    if document:
        for review in document["records"]:
            record = decisions.get(review["decision_hash"])
            if not record:
                raise ValueError("Review refers to missing, expired or ineligible decision evidence")
            decision = record["decision"]
            if (review["event_id"] != record["event_id"] or review["screen_hash"] != decision["screen_hash"]
                    or review["target_hash"] != decision.get("chosen_target_hash") or decision.get("choice_kind") != "target"):
                raise ValueError("Review does not match the captured Jev target and screen")
            if session_splits.setdefault(record["session_key"], review["split"]) != review["split"]:
                raise ValueError("Review session leakage across splits")
            record.update(review_label=review["label"], split=review["split"], case_id=review["group_id"])

    records = list(decisions.values())
    counts = Counter(r["decision"]["status"] for r in records)
    holds = Counter(r["decision"].get("hold_reason", "unspecified") for r in records
                    if r["decision"]["status"] == "low_confidence")
    repeated = defaultdict(list)
    groups = defaultdict(list)
    issues = defaultdict(list)
    for record in records:
        d = record["decision"]
        identity = (record["task_id"], record["graph_hash"], record["state_id"], record["device_cohort"],
                    record["source_kind"], d.get("model"), d.get("code_hash"), d.get("minimum_confidence"))
        groups[identity].append(record)
        if d["status"] == "low_confidence":
            repeated[(record["session_key"], record["episode_id"], record["state_id"])].append(record)
            if record.get("review_label") == "correct":
                issues["reviewed_correct_target_held"].append(record["event_id"])
            elif record.get("review_label") not in {"correct", "incorrect"}:
                issues["held_target_needs_independent_review"].append(record["event_id"])
        if (record.get("review_label") == "incorrect" and "confidence" in d and "minimum_confidence" in d
                and d["confidence"] >= d["minimum_confidence"]):
            issues["high_confidence_wrong_target"].append(record["event_id"])
        if any(outcome["label"] == "verified_failure" for outcome in record["outcomes"]):
            issues["execution_failed_check_action_and_observation"].append(record["event_id"])
        if d["status"] in {"provider_error", "timeout", "deadline_exceeded", "invalid_response"}:
            issues["provider_failure"].append(record["event_id"])
    group_reports = []
    for identity, items in sorted(groups.items(), key=lambda item: str(item[0])):
        task, graph, state, cohort, source, model, code, minimum = identity
        group_reports.append({"task_id": task, "graph_hash": graph, "state_id": state, "device_cohort": cohort,
            "source_kind": source, "model": model, "code_hash": code, "minimum_confidence": minimum,
            "provider_calls": len(items), "statuses": dict(Counter(r["decision"]["status"] for r in items)),
            "execution": _execution_metrics(items), "reviews": _review_metrics(items),
            "event_ids": [r["event_id"] for r in items]})
    for items in repeated.values():
        if len(items) > 1:
            issues["repeated_low_confidence_at_same_step"].extend(r["event_id"] for r in items)
    # Expose privacy-bounded decision references so an independent reviewer can
    # attach a label without exporting screen text, screenshots, or account IDs.
    review_queue = [{"event_id": r["event_id"], "decision_hash": r["decision"]["decision_hash"],
        "screen_hash": r["decision"]["screen_hash"], "target_hash": r["decision"]["chosen_target_hash"]}
        for r in records if r["decision"].get("choice_kind") == "target"
        and r["decision"].get("chosen_target_hash") and r.get("review_label") not in {"correct", "incorrect"}]
    return {"schema_version": "ai2-jev-assessment.v1", "assessment_policy": POLICY,
        "status": "REPORT_AVAILABLE" if records else "NEEDS_DATA", "observations": dict(observations),
        "decision_statuses": dict(counts), "low_confidence_reasons": dict(holds),
        "execution": _execution_metrics(records), "outcomes_on_overridden_or_unidentified_targets": reassigned,
        "review_splits": {split: _review_metrics([r for r in records if r.get("split") == split]) for split in sorted(SPLITS)},
        "groups": group_reports, "issues": [{"reason": reason, "count": len(set(refs)), "event_ids": sorted(set(refs))}
                                             for reason, refs in sorted(issues.items())],
        "review_queue": review_queue, "review_manifest_hash": fingerprint(document) if document else None,
        "comparison_cases": [{key: deepcopy(r[key]) for key in (
            "case_id", "task_id", "graph_hash", "device_cohort", "source_kind", "decision", "review_label", "split")}
            for r in records if r.get("review_label") in {"correct", "incorrect"}],
        "limitations": ["execution_success_is_not_target_correctness", "unexecuted_holds_have_no_execution_outcome",
                         "reviewed_samples_may_not_represent_all_traffic", "threshold_sweep_is_not_automatic_approval"]}


def compare_assessments(before, after):
    """Compare the same independently reviewed holdout screens; never approve a release."""
    from ai_agent.telemetry.jev_decisions import validate_decision
    sides = []
    for report in (before, after):
        if report.get("schema_version") != "ai2-jev-assessment.v1" or report.get("assessment_policy") != POLICY:
            raise ValueError("Matching Jev assessment policy required")
        cases = {}
        for case in report.get("comparison_cases", []):
            if case["split"] != "holdout":
                continue
            identifier(case["case_id"])
            hash_value(case["graph_hash"])
            decision = validate_decision(case["decision"])
            if (case["review_label"] not in {"correct", "incorrect"} or decision.get("choice_kind") != "target"
                    or not decision.get("chosen_target_hash")):
                raise ValueError("Independent target labels required for comparison")
            if case["case_id"] in cases:
                raise ValueError("Each comparison case needs exactly one decision per run")
            cases[case["case_id"]] = case
        sides.append(cases)
    left, right = sides
    missing = []
    if not left or set(left) != set(right):
        missing.append("same_independently_reviewed_holdout_cases")
    for key in set(left) & set(right):
        a, b = left[key], right[key]
        if any(a[field] != b[field] for field in ("task_id", "graph_hash", "device_cohort", "source_kind")) or a["decision"]["screen_hash"] != b["decision"]["screen_hash"]:
            missing.append("same_captured_screen_and_task")
    for cases in sides:
        configurations = {(c["decision"].get("model"), c["decision"].get("code_hash"),
                           c["decision"].get("minimum_confidence")) for c in cases.values()}
        if len(configurations) != 1 or any(None in config for config in configurations):
            missing.append("one_identified_runtime_configuration_per_run")
        if any(c["source_kind"] not in {"runtime", "device_test"} for c in cases.values()):
            missing.append("independent_device_or_runtime_holdout")
    if missing:
        return {"schema_version": "ai2-jev-comparison.v1", "status": "NEEDS_DATA", "missing": sorted(set(missing)),
                "automatic_changes": False}

    def metrics(cases):
        records = list(cases.values())
        reviewed = _review_metrics(records)
        reviewed["correct_targets_held"] = sum(c["review_label"] == "correct" and c["decision"]["status"] == "low_confidence" for c in records)
        reviewed["wrong_targets_accepted"] = sum(c["review_label"] == "incorrect" and c["decision"]["status"] == "selected" for c in records)
        reviewed["configuration"] = {key: records[0]["decision"][key] for key in ("model", "code_hash", "minimum_confidence")}
        return reviewed

    a, b = metrics(left), metrics(right)
    regressions = [key for key in left if (
        left[key]["review_label"] == "correct" and right[key]["review_label"] == "incorrect") or (
        right[key]["review_label"] == "incorrect" and right[key]["decision"]["status"] == "selected"
        and left[key]["decision"]["status"] != "selected")]
    return {"schema_version": "ai2-jev-comparison.v1", "status": "REVIEW_REQUIRED", "paired_cases": len(left),
            "before": a, "after": b, "accuracy_change": b["target_accuracy"] - a["target_accuracy"],
            "regressed_case_ids": sorted(regressions), "automatic_changes": False,
            "execution_success_change": None,
            "execution_note": "Changed actions require newly observed execution outcomes; target reviews do not prove task completion."}


def render_report(report):
    """A short operator-facing explanation; counts always retain their denominator."""
    execution = report["execution"]
    lines = ["Jev 판단 분석", f"실제 Jev 호출: {report['observations'].get('provider_calls', 0)}회",
             f"기존 안내 유지: {report['observations'].get('retained_guides', 0)}회 (새 판단에서 제외)",
             f"실행 결과 확인: 성공 {execution['successes']}회 / 검증된 시도 {execution['verified_attempts']}회",
             f"선택했지만 실행 결과 미확인: {execution['selected_without_verified_outcome']}건 (실패에서 제외)"]
    for split, review in report["review_splits"].items():
        lines.append(f"대상 선택 검수({split}): 정답 {review['correct_targets']}건 / 검수 {review['reviewed_targets']}건")
    names = {"reviewed_correct_target_held": "맞는 대상을 골랐지만 낮은 점수로 멈춤",
             "held_target_needs_independent_review": "보류한 대상의 정답 검수가 필요함",
             "high_confidence_wrong_target": "높은 점수를 줬지만 검수에서 틀린 대상",
             "execution_failed_check_action_and_observation": "조작 실패: 실행·관측 원인 확인 필요",
             "provider_failure": "Jev 연결 또는 응답 오류",
             "repeated_low_confidence_at_same_step": "같은 단계에서 낮은 점수로 반복 보류"}
    for issue in report["issues"]:
        lines.append(f"확인할 문제: {names[issue['reason']]} — {issue['count']}건")
    if not report["review_manifest_hash"]:
        lines.append("아직 독립 검수가 없어 대상 정답률과 불필요한 보류 여부는 확정할 수 없습니다.")
    lines.append("점수 기준·모델·경로는 자동 변경하지 않습니다.")
    return "\n".join(lines)
