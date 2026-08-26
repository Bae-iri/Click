from ai_agent.schemas import RawGoalInput
from ai_agent.workflows.runtime_loop import RuntimeLoop


def test_runtime_loop_accepts_raw_goal_input():
    result = RuntimeLoop().run(RawGoalInput(source_text="글자 크기 키우고 싶어"))

    assert result["status"] == "not_implemented"
    assert result["source_text"] == "글자 크기 키우고 싶어"
