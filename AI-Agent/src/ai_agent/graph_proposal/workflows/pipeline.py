"""Pure offline orchestration. No HTTP, model downloads, or automatic releases."""
from dataclasses import asdict
from ai_agent.graph_proposal.analysis.failures import analyze
from ai_agent.graph_proposal.contracts import fingerprint, validate_manifest
from ai_agent.graph_proposal.datasets.dataset_builder import TrainingDatasetBuilder
from ai_agent.graph_proposal.evaluation.graph_validator import GraphValidator
from ai_agent.graph_proposal.proposals.graph_learner import GraphLearner
from ai_agent.graph_proposal.reconstruction.episodes import reconstruct


def run_pipeline(events, graphs, *, now, manifest=None, cases=None, evaluator=None, graph_loader=None):
    episodes = reconstruct(events, now=now)
    reviews = {}
    if manifest is not None:
        manifest = validate_manifest(manifest)
        reviews = {r["episode_id"]: r for r in manifest["records"]}
    for episode in episodes:
        review = reviews.get(episode["episode_id"])
        episode["split"] = review["split"] if review else "unassigned"
    datasets = None
    if manifest is not None:
        # Validate group/session separation before using anything for proposals.
        datasets = TrainingDatasetBuilder().build(episodes, manifest=manifest)
    proposals = GraphLearner().generate_proposals(episodes, graphs)
    # A run can contain several tasks; each proposal needs its own task's holdout.
    # Keep malformed cases in the evaluation so invalid input cannot be silently ignored.
    evaluations = [GraphValidator().validate(proposal,
        base_graph=graphs.get(proposal.evidence["base_graph_hash"]),
        cases=[case for case in cases if not isinstance(case, dict) or case.get("task_id") in (None, proposal.task_id)] if cases else cases,
        manifest=manifest,
        evaluator=evaluator, graph_loader=graph_loader) for proposal in proposals]
    decisions = {evaluation["decision"] for evaluation in evaluations}
    status = ("ERROR" if "ERROR" in decisions else
              "READY_FOR_REVIEW" if "PASS" in decisions else
              "NEEDS_DATA" if not decisions or "NEEDS_DATA" in decisions else "REJECTED")
    result = {"schema_version": "ai2-run.v1", "episodes": episodes,
        "analysis": analyze(episodes), "dataset": datasets,
        "proposals": [asdict(proposal) for proposal in proposals], "evaluations": evaluations,
        "status": status}
    result["run_hash"] = fingerprint(result)
    return result
