import json
from pathlib import Path


CATALOG = Path(__file__).parents[1] / "goal_catalog" / "mvp_goals.json"


def test_goal_catalog_has_unique_settings_and_coupang_keyword_rules():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    goals = catalog["goals"]
    goal_types = [goal["goal_type"] for goal in goals]

    assert catalog["version"] == "2.0.0"
    assert len(goals) == 82
    assert len(goal_types) == len(set(goal_types))
    assert sum(goal["target_app"] == "android_settings" for goal in goals) == 48
    assert sum(goal["target_app"] == "coupang" for goal in goals) == 34
    assert {goal["goal_type"] for goal in goals if goal["execution_status"] == "approved"} == {
        "settings.font_size.increase",
        "settings.font_size.decrease",
    }
    assert all(goal["keyword_groups"] and all(group for group in goal["keyword_groups"]) for goal in goals)


def test_transactional_coupang_goals_are_marked_high_risk():
    goals = json.loads(CATALOG.read_text(encoding="utf-8"))["goals"]
    by_type = {goal["goal_type"]: goal for goal in goals}

    for goal_type in {
        "coupang.order.checkout",
        "coupang.order.cancel",
        "coupang.order.return",
        "coupang.order.exchange",
        "coupang.payment_method.add",
        "coupang.payment_method.change",
        "coupang.order.reorder",
        "coupang.order.gift",
    }:
        assert by_type[goal_type]["risk_level"] == "high"
        assert by_type[goal_type]["execution_status"] == "catalog_only"
