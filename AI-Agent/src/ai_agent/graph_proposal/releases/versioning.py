"""Graph version promotion and rollback boundary."""

from __future__ import annotations
from copy import deepcopy
from ai_agent.graph_proposal.contracts import fingerprint, identifier, hash_value
from ai_agent.graph_proposal.graphs import apply_proposal


class GraphVersionManager:
    def __init__(self, store=None):
        self.store = store

    def prepare(self, proposal, base_graph, *, candidate_version, evaluation, reviewer, reviewed_at):
        """Bind approval to exact base/candidate/evaluation hashes before publication."""
        identifier(candidate_version, "graph version")
        identifier(reviewer, "reviewer")
        if len(candidate_version) > 64 or candidate_version == proposal.base_graph_version:
            raise ValueError("A release requires a new version")
        if type(reviewed_at) is not int or reviewed_at <= 0:
            raise ValueError("Invalid review time")
        candidate = apply_proposal(base_graph, proposal)
        if (evaluation.get("decision") != "PASS" or evaluation.get("proposal_id") != proposal.proposal_id
                or evaluation.get("base_graph_hash") != fingerprint(base_graph)
                or evaluation.get("candidate_hash") != fingerprint(candidate)
                or not evaluation.get("product_case_ids") or evaluation.get("missing") or evaluation.get("errors")
                or not evaluation.get("checks") or evaluation.get("candidate_failures") != 0
                or evaluation.get("regression", {}).get("has_regression") is not False):
            raise ValueError("Release requires a matching PASS on independent device holdout")
        hash_value(evaluation.get("manifest_hash"))
        return {"schema_version": "ai2-release.v1", "task_id": proposal.task_id,
            "base_version": proposal.base_graph_version, "base_hash": fingerprint(base_graph),
            "version": candidate_version, "graph_hash": fingerprint(candidate), "body": candidate,
            "proposal_id": proposal.proposal_id, "evaluation_hash": fingerprint(evaluation),
            "evaluation": deepcopy(evaluation), "reviewer": reviewer, "reviewed_at": reviewed_at}

    def promote(self, release):
        if self.store is None:
            raise ValueError("A durable release store is required")
        return self.store.publish(release)

    def rollback(self, task_id, target_version, *, expected_current_hash, reviewer, reviewed_at):
        if self.store is None:
            raise ValueError("A durable release store is required")
        identifier(task_id)
        identifier(target_version)
        identifier(reviewer)
        hash_value(expected_current_hash)
        return self.store.rollback(task_id, target_version, expected_current_hash=expected_current_hash,
                                   reviewer=reviewer, reviewed_at=reviewed_at)
