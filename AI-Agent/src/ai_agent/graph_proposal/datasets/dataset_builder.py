"""Build model-specific training samples from runtime traces."""

from __future__ import annotations

from copy import deepcopy
from ai_agent.graph_proposal.contracts import fingerprint, validate_manifest


class TrainingDatasetBuilder:
    def build(self, episodes, sample_type="transition_outcome", *, manifest):
        """Keep holdout and weak/gold labels separate; never use future state as input."""
        if sample_type not in {"transition_outcome", "task_outcome"}:
            raise ValueError("Unsupported sample type")
        manifest = validate_manifest(manifest)
        reviews = {r["episode_id"]: r for r in manifest["records"]}
        splits = {name: [] for name in ("train", "validation", "holdout")}
        excluded, seen, session_splits = [], set(), {}
        for episode in episodes:
            eid = episode["episode_id"]
            if eid in seen:
                raise ValueError("Duplicate dataset episode")
            seen.add(eid)
            review = reviews.get(eid)
            if review is None:
                excluded.append({"episode_id": eid, "reason": "missing_manifest"})
                continue
            if review["source_kind"] != episode["source_kind"]:
                raise ValueError("Manifest source mismatch")
            if session_splits.setdefault(episode["session_key"], review["split"]) != review["split"]:
                raise ValueError("Session leakage across splits")
            if not episode["complete_evidence"]:
                excluded.append({"episode_id": eid, "reason": "incomplete_evidence"})
                continue
            if set(review["evidence_refs"]) - set(episode["event_ids"]):
                raise ValueError("Review refers to evidence outside episode")
            gold = review["review_status"] == "independent_review" and episode["source_kind"] in {"device_test", "runtime"}
            metadata = {"episode_id": eid, "group_id": review["group_id"], "source_kind": episode["source_kind"],
                "review_status": review["review_status"], "review_revision": review["review_revision"],
                "evidence_refs": deepcopy(review["evidence_refs"]), "product_gold": gold,
                "task_id": episode["task_id"], "graph_hash": episode["graph_hash"]}
            if sample_type == "task_outcome":
                samples = [{**metadata, "input": {"task_id": episode["task_id"], "graph_hash": episode["graph_hash"]},
                    "label": review["label"], "label_method": review["label_method"]}]
            else:
                # Review of the episode outcome is not independent review of each step.
                samples = [{**metadata, "product_gold": False, "input": {"task_id": episode["task_id"],
                    "graph_hash": episode["graph_hash"], "state_id": step["state_id"], "transition_id": step["transition_id"]},
                    "label": step["label"], "label_method": "weak", "event_id": step["event_id"]}
                    for step in episode["steps"] if step["transition_id"] and step["label"] in {"verified_success", "verified_failure"}]
            splits[review["split"]].extend(samples)
        return {"schema_version": "ai2-samples.v1", "sample_type": sample_type,
            "dataset_id": manifest["dataset_id"], "manifest_hash": fingerprint(manifest),
            "splits": splits, "excluded": excluded}
