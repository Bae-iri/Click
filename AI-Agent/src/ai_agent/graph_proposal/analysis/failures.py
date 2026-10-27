"""Counts with explicit denominators; never equate waiting with failed attempts."""
from collections import Counter, defaultdict


def analyze(episodes):
    clusters = defaultdict(lambda: {"counts": Counter(), "event_ids": [], "episode_ids": set()})
    totals = Counter()
    for episode in episodes:
        for step in episode["steps"]:
            label = step["label"]
            totals[label] += 1
            key = (episode["task_id"], episode["graph_hash"], step["state_id"], step["transition_id"], step["reason_code"])
            cluster = clusters[key]
            cluster["counts"][label] += 1
            cluster["event_ids"].append(step["event_id"])
            cluster["episode_ids"].add(episode["episode_id"])
    results = []
    for key, cluster in sorted(clusters.items(), key=lambda item: str(item[0])):
        task, graph_hash, state, transition, reason = key
        counts = dict(cluster["counts"])
        success, failure = counts.get("verified_success", 0), counts.get("verified_failure", 0)
        results.append({"task_id": task, "graph_hash": graph_hash, "state_id": state,
            "transition_id": transition, "reason_code": reason, "counts": counts,
            "attempts": success + failure, "failure_rate": failure / (success + failure) if success + failure else None,
            "event_ids": sorted(cluster["event_ids"]), "episode_ids": sorted(cluster["episode_ids"])})
    return {"episode_count": len(episodes), "counts": dict(totals), "clusters": results,
            "data_requests": [{"episode_id": e["episode_id"], "missing": e["issues"]} for e in episodes if e["issues"]]}
