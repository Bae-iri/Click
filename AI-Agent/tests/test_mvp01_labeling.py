import json
from pathlib import Path

import pytest

from ai_agent.graph_proposal.labeling.mvp01 import label_episode, label_jsonl


FIXTURE = Path(__file__).resolve().parents[1] / "data" / "fixtures" / "mvp01_episodes.jsonl"


def test_twenty_replay_cases_have_expected_task_and_step_outcomes(tmp_path):
    output = tmp_path / "labels.jsonl"
    assert label_jsonl(FIXTURE, output) == 20
    records = {r["episode_id"]: r for r in map(json.loads, output.read_text(encoding="utf-8").splitlines())}
    assert len(records) == 20
    assert records["sample-01"]["task"]["outcome_label"] == "success"
    assert records["sample-05"]["task"]["outcome_label"] == "fail"
    assert records["sample-07"]["steps"][0]["outcome_label"] == "fail"
    assert records["sample-07"]["task"]["outcome_label"] == "success"
    assert records["sample-08"]["task"]["outcome_label"] == "cancelled"
    assert records["sample-10"]["task"]["outcome_label"] == "not_applicable"
    assert records["sample-12"]["steps"][0]["outcome_label"] == "unknown"
    assert records["sample-16"]["task"]["outcome_label"] == "unknown"
    assert records["sample-17"]["task"]["outcome_label"] == "unknown"


def test_rejects_duplicate_steps_and_does_not_write_partial_output(tmp_path):
    episode = {"episode_id": "e1", "task_id": "MVP-01", "steps": [{"step_id": "s1"}, {"step_id": "s1"}]}
    with pytest.raises(ValueError, match="duplicate step_id"):
        label_episode(episode)
    source = tmp_path / "bad.jsonl"
    destination = tmp_path / "labels.jsonl"
    source.write_text(FIXTURE.read_text(encoding="utf-8") + json.dumps(episode) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="line 21"):
        label_jsonl(source, destination)
    assert not destination.exists()
