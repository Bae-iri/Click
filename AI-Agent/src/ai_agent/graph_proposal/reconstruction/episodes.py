"""Order/deduplicate immutable events and preserve missing/corrupt evidence as gaps."""
from collections import defaultdict
from ai_agent.graph_proposal.contracts import fingerprint, validate_event
from ai_agent.graph_proposal.ingestion.runtime_events import label_event


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
            identities = {(e["task_id"], e["graph_version"], e["graph_hash"], e["source_kind"]) for e in group}
            if len(identities) != 1:
                raise ValueError("Graph/source changed within episode")
            task_id, version, graph_hash, source_kind = identities.pop()
            guides, attempts, steps, issues = {}, set(), [], []
            if episode_id in missing:
                issues.append("missing_session_events")
            for event in group:
                runtime = event["runtime"]
                if not runtime:
                    continue
                label = label_event({"runtime": runtime})
                previous_guide = guides.get(runtime.get("previous_guide_hash"))
                attempt = runtime.get("attempt_hash")
                before, after = runtime.get("before_observation_hash"), runtime.get("after_observation_hash")
                correlated = bool(attempt and previous_guide and before and after and before != after
                    and previous_guide.get("after_observation_hash") == before
                    and previous_guide.get("target", {}).get("node_hash") == runtime.get("attempt_target_hash"))
                if label["label"] in {"verified_success", "verified_failure"} and not correlated:
                    label = {**label, "label": "unknown", "reason_code": "missing_linked_evidence"}
                    issues.append("missing_linked_evidence")
                if attempt and attempt in attempts:
                    label = {**label, "label": "duplicate_attempt"}
                elif runtime.get("count_attempt") and attempt:
                    attempts.add(attempt)
                target = (previous_guide or {}).get("target", {})
                steps.append({"event_id": event["event_id"], "sequence": event["sequence"],
                    "state_id": (previous_guide or {}).get("state_id"),
                    "transition_id": target.get("transition_id"), "attempt_hash": attempt,
                    "before_observation_hash": before, "after_observation_hash": after,
                    "label": label["label"], "reason_code": label.get("reason_code"),
                    "task_completed": bool(runtime.get("task_completed")),
                    "before_values": runtime.get("before_values", {}), "after_values": runtime.get("after_values", {})})
                if runtime.get("guide_hash"):
                    if runtime["guide_hash"] in guides:
                        raise ValueError("Guide ID reused")
                    guides[runtime["guide_hash"]] = runtime
            episodes.append({"episode_id": episode_id, "session_key": session_key,
                "task_id": task_id, "graph_version": version, "graph_hash": graph_hash,
                "source_kind": source_kind, "event_ids": [e["event_id"] for e in group],
                "expires_at": min(e["expires_at"] for e in group), "steps": steps,
                "issues": sorted(set(issues)), "complete_evidence": not issues,
                "task_label": "weak_success" if any(s["task_completed"] and s["label"] == "verified_success" for s in steps) else "unknown",
                "user_results": [e["control"] for e in group if e["control"]]})
    return episodes
