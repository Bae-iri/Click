"""A non-target setting change must not turn a valid menu choice into abstention."""
from dataclasses import replace
import pytest
from app.clients.checkpoint import capture, restore
from tests.test_settings_stability_matrix import Run, GOALS
from tests.test_screen_jev import sender_for


@pytest.fixture(autouse=True)
def configuration(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY', 'synthetic-test-key')
    monkeypatch.setenv('JEV_MODEL', 'jev-1.14.0')


@pytest.mark.parametrize('setting,direction', GOALS)
@pytest.mark.parametrize('persisted', [False, True])
def test_unchanged_native_menu_keeps_its_jev_choice_when_unrelated_values_change(setting, direction, persisted):
    run = Run(setting, direction)
    run.edge = run.home_edge
    calls = []
    def send(payload, **kwargs):
        calls.append(payload)
        def confidence_fluctuation(response):
            if len(calls) > 1:
                response['answers']['screen']['confidence'] = .79
        return sender_for(run.edge, mutate=confidence_fluctuation)(payload, **kwargs)
    run.resolver._sender = send
    first = run.observe(run.home)
    assert first.guide_command.action_type.value == 'tap'
    if persisted:
        checkpoint = capture(run.engine.session_store, 's', run.graph)
        run.engine.session_store = restore(checkpoint)
    unrelated = 'media_volume' if setting == 'brightness' else 'screen_brightness'
    second = run.observe(run.home, applied={**run.applied, unrelated: run.applied[unrelated] + 1})
    assert len(calls) == 1
    assert second.debug['ui_selection']['retained'] is True
    assert second.guide_command.action_type.value == 'tap'
    assert second.guide_command.target_node_id == first.guide_command.target_node_id


@pytest.mark.parametrize('change', ['bounds', 'resource', 'window', 'label', 'enabled'])
def test_menu_choice_is_not_retained_after_native_target_changes(change):
    run = Run('font_size', 'increase')
    run.edge = run.home_edge
    first = run.observe(run.home)
    assert first.guide_command.action_type.value == 'tap'
    replacements = {
        'bounds': {'bounds_in_screen': (20, 400, 900, 490)},
        'resource': {'resource_id': 'com.android.settings:id/another_control'},
        'window': {'window_id': 8},
        'label': {'text': '접근성'},
        'enabled': {'enabled': False},
    }
    nodes = (run.home[0], replace(run.home[1], **replacements[change]))
    second = run.observe(nodes)
    assert not second.debug['ui_selection'].get('retained', False)


def test_legacy_checkpoint_without_native_fingerprint_requires_a_new_selection():
    run = Run('font_size', 'increase')
    run.edge = run.home_edge
    run.observe(run.home)
    checkpoint = capture(run.engine.session_store, 's', run.graph)
    checkpoint['last_snapshot'].pop('native_fingerprint', None)
    run.engine.session_store = restore(checkpoint)
    run.observe(run.home, applied={**run.applied, 'screen_brightness': 101})
    assert len(run.calls) == 2


@pytest.mark.parametrize('setting,direction', GOALS)
@pytest.mark.parametrize('change', ['native_value', 'applied_value'])
def test_setting_control_is_reselected_when_its_value_or_applied_evidence_changes(setting, direction, change):
    run = Run(setting, direction)
    run.route()
    before = len(run.calls)
    nodes = run.control
    applied = dict(run.applied)
    if change == 'native_value':
        nodes = tuple(replace(node, extras={**node.extras, 'progress': 4})
                      if node.node_id == 'target' else node for node in nodes)
    else:
        key = {'font_size': 'font_scale', 'brightness': 'screen_brightness', 'volume': 'media_volume'}[setting]
        applied[key] += .1 if setting == 'font_size' else 1
    response = run.observe(nodes, applied=applied)
    assert not response.debug['ui_selection'].get('retained', False)
    assert len(run.calls) > before
