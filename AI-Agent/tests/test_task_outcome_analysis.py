from ai_agent.graph_proposal.outcomes import summarize_task_outcomes


def test_agent2_keeps_user_reports_and_verified_changes_separate():
    records = [{"task_outcome": {"technical": {"status": technical, "reason": "sample"},
                "user": {"status": user}}} for technical, user in [
                    ("CHANGED", "satisfied"), ("CHANGED", "needs_help"),
                    ("UNVERIFIABLE", "satisfied"), ("NOT_ACHIEVED", "stopped"),
                    ("CHANGED", "no_response")]]
    summary = summarize_task_outcomes(records)
    assert summary["verified_goal_achievements"] == 1
    assert summary["technical"]["CHANGED"] == 3
    assert summary["user"]["satisfied"] == 2
    assert summary["user"]["no_response"] == 1
