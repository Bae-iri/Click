from ai_agent.graph_proposal.datasets.android_control import split_for_episode, load_official_splits
import json
import pytest


def test_episode_split_is_stable_and_exclusive(tmp_path):
    path = tmp_path / "split.json"
    path.write_text(json.dumps({"train": [1], "validation": [2], "test": [3]}))
    official = load_official_splits(path)
    assert [split_for_episode(i, official) for i in (1, 2, 3)] == ["train", "validation", "test"]
    assert split_for_episode(123) == split_for_episode(123)
    with pytest.raises(ValueError):
        split_for_episode(4, official)
    path.write_text(json.dumps({"train": [1], "validation": [1], "test": []}))
    with pytest.raises(ValueError):
        load_official_splits(path)
