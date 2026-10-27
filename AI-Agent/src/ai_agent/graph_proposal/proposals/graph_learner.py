"""Trace-driven Task Graph update proposal generation."""

from __future__ import annotations

from ai_agent.graph_proposal.schemas import GraphUpdateProposal
from collections import defaultdict, Counter
from ai_agent.graph_proposal.contracts import fingerprint
from ai_agent.graph_proposal.graphs import graph_errors


class GraphLearner:
    def generate_proposals(self, episodes, graphs=None, *, min_attempts=2):
        """Conservative existing-route ranking from linked development attempts.

        min_attempts is a development heuristic, not a product acceptance gate.
        Missing text/targets cannot justify inventing selectors or new UI edges.
        """
        if type(min_attempts) is not int or min_attempts < 1:
            raise ValueError("min_attempts must be positive")
        graphs = graphs or {}
        groups = defaultdict(list)
        for episode in episodes:
            if not isinstance(episode, dict):
                raise ValueError("Reconstructed episodes with linked evidence are required")
            if episode.get("split") not in {"train", "validation"} or not episode["complete_evidence"]:
                continue
            groups[(episode["task_id"], episode["graph_hash"], episode["graph_version"])].append(episode)
        proposals = []
        for (task_id, base_hash, version), group in sorted(groups.items()):
            body = graphs.get(base_hash)
            if body is None or graph_errors(body) or fingerprint(body) != base_hash:
                continue
            counts, evidence = defaultdict(Counter), defaultdict(list)
            for episode in group:
                for step in episode["steps"]:
                    if step["label"] in {"verified_success", "verified_failure"}:
                        counts[step["transition_id"]][step["label"]] += 1
                        evidence[step["transition_id"]].append(step["event_id"])
            states = defaultdict(list)
            for edge in body["transitions"]:
                states[edge["from_state"]].append(edge)
            for state, edges in sorted(states.items()):
                observed = [e for e in edges if sum(counts[e["transition_id"]].values()) >= min_attempts]
                if len(observed) < 2:
                    continue
                def rate(edge):
                    stats = counts[edge["transition_id"]]
                    return stats["verified_success"] / sum(stats.values())
                observed.sort(key=lambda e: (-rate(e), e.get("priority", 0), e["transition_id"]))
                current = sorted(edges, key=lambda e: (e.get("priority", 0), e["transition_id"]))
                best = observed[0]
                if current[0] == best or rate(best) <= rate(observed[-1]) or not counts[current[0]["transition_id"]]["verified_failure"]:
                    continue
                order = [best] + [e for e in current if e != best]
                priorities = {e["transition_id"]: index for index, e in enumerate(order)}
                change = {"priorities": priorities}
                refs = sorted({ref for e in observed for ref in evidence[e["transition_id"]]})
                proposal_id = "priority-" + fingerprint({"base": base_hash, "change": change, "events": refs})[:24]
                proposals.append(GraphUpdateProposal(proposal_id=proposal_id, task_id=task_id,
                    base_graph_version=version, proposal_type="transition_priority", change=change,
                    evidence={"base_graph_hash": base_hash, "state_id": state, "event_ids": refs,
                        "episode_ids": sorted(e["episode_id"] for e in group),
                        "gate_origin": "development_heuristic", "min_attempts": min_attempts,
                        "source_kinds": sorted({e["source_kind"] for e in group})},
                    proposal_confidence=rate(best), requires_human_review=True))
        return proposals
