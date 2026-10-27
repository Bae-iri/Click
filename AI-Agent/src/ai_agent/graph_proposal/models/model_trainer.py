"""Model training/evaluation boundary."""

from __future__ import annotations
from collections import Counter, defaultdict
from ai_agent.graph_proposal.contracts import fingerprint


class ModelTrainer:
    def train(self, dataset, model_type="transition_frequency", *, allow_weak=False):
        """CPU baseline, not an LLM. Holdout is never read during fitting."""
        if model_type != "transition_frequency" or not isinstance(dataset, dict):
            raise ValueError("Only transition_frequency with a versioned dataset is supported")
        if dataset.get("schema_version") != "ai2-samples.v1" or dataset.get("sample_type") != "transition_outcome":
            raise ValueError("Transition outcome dataset required")
        if not allow_weak:
            raise ValueError("Step outcomes are weak labels; explicitly enable allow_weak for this baseline")
        counts = defaultdict(Counter)
        samples = dataset["splits"]["train"]
        for sample in samples:
            if sample["label"] not in {"verified_success", "verified_failure"}:
                continue
            key = fingerprint(sample["input"])
            counts[key][sample["label"]] += 1
        if not counts:
            return {"status": "NEEDS_DATA", "missing": ["training_attempts"]}
        artifact = {"schema_version": "ai2-model.v1", "model_type": model_type,
            "manifest_hash": dataset["manifest_hash"], "training_hash": fingerprint(samples),
            "deployment_status": "candidate", "label_method": "weak",
            "entries": {key: {"successes": count["verified_success"], "failures": count["verified_failure"],
                "smoothed_success_rate": (count["verified_success"] + 1) / (sum(count.values()) + 2)}
                for key, count in sorted(counts.items())},
            "training_samples": sum(sum(c.values()) for c in counts.values())}
        artifact["model_id"] = "frequency-" + fingerprint(artifact)[:24]
        return {"status": "CANDIDATE", "artifact": artifact}
