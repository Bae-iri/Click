"""Offline Agent 2 input summary. No training, graph mutation, or deployment."""
from collections import Counter


def summarize_task_outcomes(records):
    technical, user, reasons = Counter(), Counter(), Counter()
    achieved = 0
    for record in records:
        outcome = record.get("task_outcome") or record.get("outcome") or {}
        verification = outcome.get("technical") or {}
        answer = outcome.get("user") or {}
        technical[verification.get("status", "UNVERIFIABLE")] += 1
        user[answer.get("status") or "pending"] += 1
        if verification.get("reason"):
            reasons[verification["reason"]] += 1
        achieved += int(verification.get("status") == "CHANGED" and answer.get("status") == "satisfied")
    return {"technical": dict(technical), "user": dict(user), "evidence_reasons": dict(reasons),
            "verified_goal_achievements": achieved}
