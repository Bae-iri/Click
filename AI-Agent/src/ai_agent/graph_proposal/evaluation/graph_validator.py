"""Offline replay and regression validation for graph proposals."""

from __future__ import annotations

from ai_agent.graph_proposal.schemas import GraphUpdateProposal
from ai_agent.graph_proposal.contracts import EvidenceRequired, fingerprint, validate_manifest
from ai_agent.graph_proposal.graphs import apply_proposal, graph_errors


class GraphValidator:
    def validate(self, proposal: GraphUpdateProposal, *, base_graph=None, cases=None, manifest=None,
                 evaluator=None, graph_loader=None):
        """Replay both versions against independently reviewed held-out evidence.

        evaluator(body, case) -> {checks, failures}. The runtime adapter lives
        outside Agent 2. An adapter is mandatory; missing data is never a pass.
        """
        report = {"schema_version": "ai2-evaluation.v1", "proposal_id": proposal.proposal_id,
            "decision": "NEEDS_DATA", "base_graph_hash": proposal.evidence.get("base_graph_hash"),
            "candidate_hash": None, "manifest_hash": None, "checks": 0, "case_ids": [],
            "product_case_ids": [], "synthetic_case_ids": [], "missing": [], "errors": [],
            "baseline_failures": 0, "candidate_failures": 0,
            "regression": {"has_regression": None, "failed_cases": []}}
        if base_graph is None:
            report["missing"] = ["base_graph"]
            return report
        try:
            if graph_errors(base_graph):
                raise ValueError("Invalid base graph")
            candidate = apply_proposal(base_graph, proposal)
            report["candidate_hash"] = fingerprint(candidate)
            if graph_loader:
                graph_loader(base_graph)
                graph_loader(candidate)
        except (ValueError, TypeError, KeyError) as exc:
            report.update(decision="REJECT", errors=[str(exc)])
            return report
        if manifest is None or evaluator is None or graph_loader is None or not cases:
            report["missing"] = [key for key, value in (("manifest", manifest), ("replay_cases", cases),
                ("runtime_evaluator", evaluator), ("runtime_graph_loader", graph_loader)) if not value]
            return report
        try:
            manifest = validate_manifest(manifest)
            report["manifest_hash"] = fingerprint(manifest)
            reviews = {r["episode_id"]: r for r in manifest["records"]}
            used = set()
            for case in cases:
                eid = case["episode_id"]
                if eid in used:
                    raise ValueError("Duplicate evaluation case")
                used.add(eid)
                review = reviews.get(eid)
                if not review or review["split"] != "holdout":
                    raise ValueError("Evaluation case is not declared holdout")
                if eid in proposal.evidence.get("episode_ids", []):
                    raise ValueError("Proposal evidence leaked into holdout")
                if review["review_status"] != "independent_review" or not review["evidence_refs"]:
                    report["missing"].append("independent_review:" + eid)
                    continue
                if (case.get("source_kind") != review["source_kind"]
                        or case.get("graph_hash") != fingerprint(base_graph)
                        or case.get("task_id") != proposal.task_id
                        or case.get("review_revision") != review["review_revision"]
                        or set(case.get("evidence_refs", [])) != set(review["evidence_refs"])):
                    raise ValueError("Replay case provenance mismatch")
                baseline = evaluator(base_graph, case)
                evaluated = evaluator(candidate, case)
                for result in (baseline, evaluated):
                    if (not isinstance(result, dict) or type(result.get("checks")) is not int
                            or result["checks"] <= 0 or not isinstance(result.get("failures"), list)):
                        raise ValueError("Evaluator returned no scored independent expectations")
                if baseline["checks"] != evaluated["checks"]:
                    raise ValueError("Evaluation coverage mismatch")
                report["checks"] += evaluated["checks"]
                report["case_ids"].append(eid)
                report["baseline_failures"] += len(baseline["failures"])
                report["candidate_failures"] += len(evaluated["failures"])
                report["regression"]["failed_cases"].extend(
                    {"episode_id": eid, "failure": failure} for failure in evaluated["failures"])
                key = "product_case_ids" if review["source_kind"] in {"device_test", "runtime"} else "synthetic_case_ids"
                report[key].append(eid)
            if not report["product_case_ids"]:
                report["missing"].append("independent_device_holdout")
            if not report["checks"]:
                report["missing"].append("scored_replay_expectations")
            report["regression"]["has_regression"] = bool(report["candidate_failures"]) if report["checks"] else None
            report["decision"] = ("REJECT" if report["candidate_failures"] else
                "NEEDS_DATA" if report["missing"] else "PASS")
            report["gate_origin"] = "product_risk_requirement:zero_observed_holdout_violations"
            report["limitations"] = "PASS applies only to the declared reviewed cases; it is not a population accuracy claim."
        except EvidenceRequired as exc:
            report["missing"].append(str(exc))
            report["decision"] = "NEEDS_DATA"
        except Exception as exc:
            report.update(decision="ERROR", errors=[type(exc).__name__ + ": " + str(exc)])
        return report
