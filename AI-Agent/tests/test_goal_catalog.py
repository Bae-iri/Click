import json
from pathlib import Path


CATALOG = Path(__file__).parents[1] / "goal_catalog" / "mvp_goals.json"
CATALOG_DOC = Path(__file__).parents[2] / "docs" / "goal-type-keyword-catalog.md"

PUBLIC_GOVERNMENT_GOALS = {
    'gov24.login', 'gov24.resident_copy.open', 'gov24.resident_extract.open',
    'gov24.move_in.open', 'gov24.building_register.open',
    'gov24.land_register.open', 'gov24.vehicle_register.open',
}


def test_goal_catalog_has_unique_settings_and_government_keyword_rules():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    goals = catalog["goals"]
    goal_types = [goal["goal_type"] for goal in goals]

    assert catalog["version"] == "2.6.0"
    assert len(goals) == 139
    assert len(goal_types) == len(set(goal_types))
    assert sum(goal["target_app"] == "android_settings" for goal in goals) == 111
    assert sum(goal["target_app"] == "coupang" for goal in goals) == 8
    assert sum(goal["target_app"] == "gov24" for goal in goals) == 20
    assert {g['goal_type'] for g in goals if g['execution_status']=='public_guide'} == PUBLIC_GOVERNMENT_GOALS
    assert sum(g['execution_status']=='catalog_only' for g in goals) == 20-len(PUBLIC_GOVERNMENT_GOALS)
    assert sum(g['execution_status'] == 'approved' and g['task_type'] == 'settings_change' for g in goals) == 56
    assert sum(g['execution_status'] == 'approved' and g['task_type'] == 'settings_assisted' for g in goals) == 34
    assert all(goal["keyword_groups"] and all(group for group in goal["keyword_groups"]) for goal in goals)


def test_reactivated_coupang_goals_are_unchanged_page_navigation_contracts():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    archived = {g["goal_type"]: g for g in json.loads((CATALOG.parent/'legacy_coupang_goals.json').read_text(encoding="utf-8"))["goals"]}
    tasks = json.loads((CATALOG.parent/'commerce_execution.json').read_text(encoding="utf-8"))["tasks"]
    active = [g for g in catalog["goals"] if g["target_app"] == "coupang"]
    assert {g["goal_type"] for g in active} == {
        "coupang.cart.view", "coupang.order.list", "coupang.wishlist.view", "coupang.coupon.view",
        "coupang.category.browse", "coupang.customer_service.open", "coupang.search.recent.view",
        "coupang.rocket_wow.benefits"}
    for goal in active:
        assert goal == archived[goal["goal_type"]]
        assert tasks[goal["goal_type"].removeprefix("coupang.")].get("mode") == "navigation"


def test_archived_coupang_goals_keep_their_original_risk_and_graph_contracts():
    goals = json.loads((CATALOG.parent/'legacy_coupang_goals.json').read_text(encoding="utf-8"))["goals"]
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
        "coupang.order.address.change",
        "coupang.review.delete",
        "coupang.address.remove",
        "coupang.payment_method.remove",
        "coupang.search.recent.clear",
        "coupang.account.logout",
        "coupang.recurring_delivery.manage",
        "coupang.coupay.money.add",
    }:
        assert by_type[goal_type]["risk_level"] == "high"
        assert by_type[goal_type]["execution_status"] == "approved"
        assert by_type[goal_type]['graph_id'].startswith('CP-')


def test_government_catalog_does_not_promise_unimplemented_issuance():
    goals = json.loads(CATALOG.read_text(encoding='utf-8'))['goals']
    government = {g['goal_type']:g for g in goals if g['target_app']=='gov24'}
    assert {k for k,g in government.items() if g['execution_status']=='public_guide'} == PUBLIC_GOVERNMENT_GOALS
    for goal_type in PUBLIC_GOVERNMENT_GOALS:
        assert government[goal_type]['completion_scope']=='public_screen_only'
    assert all(g['completion_scope']=='not_implemented' for g in government.values() if g['execution_status']=='catalog_only')
    for entry in government.values():
        assert entry['graph_id'] is None and entry['slots'] == {}
        assert entry['completion_scope'] in {'public_screen_only','not_implemented'}


def test_generated_goal_catalog_doc_contains_every_goal_and_keyword():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    document = CATALOG_DOC.read_text(encoding="utf-8")

    assert f"Catalog version: `{catalog['version']}`" in document
    assert f"전체 Goal: **{len(catalog['goals'])}개**" in document
    for goal in catalog["goals"]:
        assert f"`{goal['goal_type']}`" in document
        for group in goal["keyword_groups"]:
            for keyword in group:
                assert keyword in document
