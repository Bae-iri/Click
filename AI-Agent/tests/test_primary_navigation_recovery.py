"""Primary screen decisions must preserve the recovery contract consumed by Android."""
from unittest.mock import Mock

from ai_agent.runtime.types import ActionType
from test_settings_navigation import engine_for, send, settings_screen, HOME


def abstaining_engine():
    engine = engine_for()
    engine.screen_resolver = Mock(resolve=Mock(return_value=None), last_diagnostics={
        'provider': 'jev', 'mode': 'primary', 'called': True, 'status': 'abstained'})
    return engine


def test_primary_abstention_preserves_offscreen_list_search_reason():
    engine = abstaining_engine()
    tree = settings_screen('설정', (('connections', '연결', 'Wi-Fi, 블루투스'),))
    result = send(engine, tree, 1, 'font size')
    guide = result.guide_command
    assert guide.action_type == ActionType.OBSERVE_ONLY
    assert guide.target_node_id is None
    assert guide.target_bounds is None
    assert guide.render_policy['ui_selector'] == 'jev'
    recovery = guide.render_policy['recovery']
    assert recovery['reason_code'] == 'TARGET_NOT_VISIBLE'
    assert recovery['next_action'] == 'manual_navigation'
    assert recovery['waiting_for'] == 'user'
    assert 'fallback_action' not in guide.render_policy


def test_primary_wait_does_not_scroll_an_ambiguous_visible_target():
    engine = abstaining_engine()
    tree = settings_screen('설정', (('display1', '디스플레이', ''), ('display2', '디스플레이', '')))
    result = send(engine, tree, 1, 'font size')
    assert result.guide_command.target_node_id is None
    recovery = result.guide_command.render_policy['recovery']
    assert recovery['reason_code'] == 'TARGET_AMBIGUOUS'
    assert recovery['next_action'] != 'manual_navigation'


def test_primary_provider_failure_never_executes_the_rule_selected_target():
    engine = abstaining_engine()
    result = send(engine, HOME, 1, 'font size')
    assert result.guide_command.action_type == ActionType.OBSERVE_ONLY
    assert result.guide_command.target_node_id is None
    assert result.guide_command.target_bounds is None
