"""Order/deduplicate immutable events and preserve missing/corrupt evidence as gaps."""
from collections import defaultdict
from ai_agent.graph_proposal.contracts import fingerprint, validate_event
from ai_agent.graph_proposal.ingestion.runtime_events import label_event

RECONSTRUCTION_POLICY = "guide-evidence.v2"


def reconstruct(events, *, now):
    sessions, ids, positions = defaultdict(list), {}, {}
    for raw in events:
        event = validate_event(raw)
        if event["expires_at"] <= now:
            continue
        event_id, signature = event["event_id"], fingerprint(event)
        if event_id in ids:
            if ids[event_id] != signature:
                raise ValueError("Event ID reused with different evidence")
            continue
        ids[event_id] = signature
        position = (event["session_key"], event["sequence"])
        if position in positions:
            raise ValueError("Conflicting session sequence")
        positions[position] = event_id
        sessions[event["session_key"]].append(event)
    episodes = []
    for session_key, records in sorted(sessions.items()):
        records.sort(key=lambda e: e["sequence"])
        by_episode = defaultdict(list)
        missing = set()
        previous = 0
        for event in records:
            if event["sequence"] != previous + 1:
                missing.add(event["episode_id"])
            previous = event["sequence"]
            by_episode[event["episode_id"]].append(event)
        for episode_id, group in by_episode.items():
            identities = {(e["task_id"], e["graph_version"], e["graph_hash"], e["source_kind"],
                           e.get("device_cohort", "unknown:android-unknown")) for e in group}
            if len(identities) != 1:
                raise ValueError("Graph/source changed within episode")
            task_id, version, graph_hash, source_kind, cohort = identities.pop()
            guides, attempts, steps, issues = {}, set(), [], []
            guide_observations = defaultdict(set)
            if episode_id in missing:
                issues.append("missing_session_events")
            for event in group:
                runtime = event["runtime"]
                if not runtime:
                    continue
                label = label_event({"runtime": runtime})
                previous_guide_hash = runtime.get("previous_guide_hash")
                previous_guide = guides.get(previous_guide_hash)
                attempt = runtime.get("attempt_hash")
                before, after = runtime.get("before_observation_hash"), runtime.get("after_observation_hash")
                correlated = bool(attempt and previous_guide and before and after and before != after
                    and before in guide_observations[previous_guide_hash]
                    and previous_guide.get("after_observation_hash") != after
                    and previous_guide.get("target", {}).get("node_hash") == runtime.get("attempt_target_hash"))
                duplicate = bool(attempt and attempt in attempts)
                if label["label"] in {"verified_success", "verified_failure"} and not correlated and not duplicate:
                    label = {**label, "label": "unknown", "reason_code": "missing_linked_evidence"}
                    issues.append("missing_linked_evidence")
                if duplicate:
                    label = {**label, "label": "duplicate_attempt"}
                elif label["label"] in {"verified_success", "verified_failure"} and attempt:
                    attempts.add(attempt)
                target = (previous_guide or {}).get("target", {})
                # A step compares the original guide's screen with its result.
                # Polling observations prove continuity without replacing that baseline.
                baseline = previous_guide if correlated else None
                selection = (baseline or {}).get("jev_decision", {})
                steps.append({"event_id": event["event_id"], "sequence": event["sequence"],
                    "state_id": (previous_guide or {}).get("state_id"),
                    "transition_id": target.get("transition_id"), "attempt_hash": attempt,
                    "before_observation_hash": baseline["after_observation_hash"] if baseline else before,
                    "after_observation_hash": after,
                    "label": label["label"], "reason_code": label.get("reason_code"),
                    "decision_hash": selection.get("decision_hash"),
                    "model_target_executed": (target.get("node_hash") == selection["chosen_target_hash"]
                                              if selection.get("chosen_target_hash") else None),
                    "duration_ms": runtime.get("duration_ms"),
                    "task_completed": bool(runtime.get("task_completed")),
                    "before_values": baseline.get("after_values", {}) if baseline else runtime.get("before_values", {}),
                    "after_values": runtime.get("after_values", {})})
                if runtime.get("guide_hash"):
                    guide_hash = runtime["guide_hash"]
                    if guide_hash in guides:
                        # Polling a pending operation legitimately keeps its ID.
                        # A changed target under that ID remains a binding error.
                        old_target = guides[guide_hash].get("target") or {}
                        new_target = runtime.get("target") or {}
                        if any(old_target.get(k) != new_target.get(k) for k in ("node_hash", "transition_id")):
                            raise ValueError("Guide ID reused with a different target")
                        if before in guide_observations[guide_hash] and after:
                            guide_observations[guide_hash].add(after)
                    else:
                        guides[guide_hash] = runtime
                        if after:
                            guide_observations[guide_hash].add(after)
            episodes.append({"episode_id": episode_id, "session_key": session_key,
                "task_id": task_id, "graph_version": version, "graph_hash": graph_hash,
                "source_kind": source_kind, "device_cohort": cohort,
                "event_ids": [e["event_id"] for e in group],
                "expires_at": min(e["expires_at"] for e in group), "steps": steps,
                "issues": sorted(set(issues)), "complete_evidence": not issues,
                "task_label": "weak_success" if any(s["task_completed"] and s["label"] == "verified_success" for s in steps) else "unknown",
                "user_results": [e["control"] for e in group if e["control"]]})
    return episodes
