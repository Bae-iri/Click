"""Regressions for settings requests made on existing pages and OEM handoffs."""
from unittest.mock import Mock

import pytest

from ai_agent.runtime.classifier import classify_screen_state
from ai_agent.runtime.environment import classify_environment, environment_recovery
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext
from app.task_graphs import approved_graphs
from tests.test_settings_execution import start, tree
from tests.test_task_outcomes import Flow
from tests.test_visible_settings_navigation import screen


@pytest.mark.parametrize('text,goal', [
    ('핫스팟 비번 바꿔줘', 'settings.hotspot.password.change'),
    ('하스팟 비번 바꿔줘', 'settings.hotspot.password.change'),
    ('SIM 추가하기', 'settings.sim.manage'),
    ('eSIM 추가해줘', 'settings.sim.manage'),
    ('프라이빗 DNS 바꿔줘', 'settings.private_dns.change'),
    ('현재 위치 켜줘', 'settings.location.enable'),
    ('모노오디오 켜줘', 'settings.mono_audio.enable'),
    ('실시간 자막 켜줘', 'settings.captions.enable'),
])
def test_reported_requests_prepare_without_a_model(text, goal):
    flow = Flow()
    flow.app.state.turn_service.goal_preparation.llm = Mock()
    flow.app.state.turn_service.goal_preparation.llm.interpret_inputs.side_effect = AssertionError('Explicit request')
    body = flow.resolve(text)
    assert body['goal'] and body['goal']['goal_type'] == goal, body
    assert body['preparation']['status'] == 'ready', body


@pytest.mark.parametrize('title', ['청각 보조', 'Hearing enhancements', '오디오 조정', 'Audio adjustment'])
def test_mono_page_is_unambiguous_even_before_the_switch_scrolls_into_view(title):
    graph = approved_graphs()['settings.mono_audio.enable']
    nodes = (AccessibilityNode('title', text=title, package_name='com.android.settings',
        class_name='android.widget.TextView', bounds_in_screen=(20, 40, 900, 120)),)
    assert classify_screen_state(normalize_accessibility_tree(nodes), graph) == 'S_TARGET'


@pytest.mark.parametrize('goal,title,next_label', [
    ('settings.hotspot.password.change', '네트워크 및 인터넷', '핫스팟 및 테더링'),
    ('settings.hotspot.password.change', '핫스팟 및 테더링', 'Wi-Fi hotspot'),
    ('settings.captions.enable', '청각 보조', '실시간 자막'),
    ('settings.mono_audio.enable', '접근성', '청각 보조'),
    ('settings.private_dns.change', '기타 연결 설정', '프라이빗 DNS'),
    ('settings.private_dns.change', '네트워크 및 인터넷', '비공개 DNS'),
    ('settings.sim.manage', '연결', 'SIM 카드 관리자'),
    ('settings.storage.cleanup', '디바이스 케어', '저장공간'),
])
def test_request_on_existing_settings_page_targets_the_next_menu(goal, title, next_label):
    _, body = start(goal, screen(title, [('next', next_label, None)]))
    assert body['guide_command']['action_type'] == 'tap', body
    assert body['guide_command']['target_node_id'] == 'next', body
    assert not body['guide_command']['render_policy'].get('recovery_action'), body


@pytest.mark.parametrize('goal', ['settings.mono_audio.enable', 'settings.captions.enable', 'settings.location.enable'])
def test_request_started_on_its_control_page_keeps_native_confirmation(goal):
    _, body = start(goal, tree(goal, False))
    assert body['task_status'] == 'WAITING_CONFIRMATION', body
    assert body['guide_command']['target_node_id'] == 'master', body


def preference_screen(title, label, clickable=True):
    observation = screen(title, [])
    observation['nodes'].extend([
        dict(node_id='row', parent_id='list', package_name='com.android.settings',
            class_name='android.widget.LinearLayout', clickable=clickable,
            bounds_in_screen=[0, 260, 1080, 460]),
        dict(node_id='label', parent_id='row', package_name='com.android.settings',
            class_name='android.widget.TextView', text=label, resource_id='android:id/title',
            bounds_in_screen=[40, 280, 700, 360]),
        dict(node_id='master', parent_id='row', package_name='com.android.settings',
            class_name='android.widget.Switch', resource_id='android:id/switch_widget',
            clickable=True, checkable=True, extras={'checked': False},
            bounds_in_screen=[800, 280, 1000, 380]),
        dict(node_id='unrelated', parent_id='list', package_name='com.android.settings',
            class_name='android.widget.Switch', text='다른 기능', clickable=True, checkable=True,
            extras={'checked': False}, bounds_in_screen=[800, 520, 1000, 620]),
    ])
    return observation


@pytest.mark.parametrize('goal,title,label', [
    ('settings.mono_audio.enable', '청각 보조', '모노 오디오'),
    ('settings.captions.enable', '실시간 자막', '실시간 자막 사용'),
    ('settings.location.enable', '위치', '위치 사용'),
])
@pytest.mark.parametrize('clickable', [True, False])
def test_native_switch_uses_its_own_preference_title(goal, title, label, clickable):
    _, body = start(goal, preference_screen(title, label, clickable))
    assert body['task_status'] == 'WAITING_CONFIRMATION', body
    assert body['guide_command']['target_node_id'] == 'master', body


@pytest.mark.parametrize('problem', ['wrong_label', 'other_window', 'unchecked_evidence_missing',
    'multiple_switches', 'vision_title', 'vision_switch', 'outside_row'])
def test_switch_title_cannot_cross_native_identity_boundaries(problem):
    from dataclasses import replace
    observation = preference_screen('청각 보조', '모노 오디오')
    nodes = [AccessibilityNode(**{**node, 'bounds_in_screen': tuple(node['bounds_in_screen'])})
        for node in observation['nodes']]
    master = next(i for i,n in enumerate(nodes) if n.node_id == 'master')
    label = next(i for i,n in enumerate(nodes) if n.node_id == 'label')
    if problem == 'wrong_label':nodes[master] = replace(nodes[master], text='Bluetooth')
    if problem == 'other_window':nodes[master] = replace(nodes[master], window_id=99)
    if problem == 'unchecked_evidence_missing':nodes[master] = replace(nodes[master], extras={})
    if problem == 'multiple_switches':nodes.append(replace(nodes[master], node_id='duplicate'))
    if problem == 'vision_title':nodes[label] = replace(nodes[label], extras={'vision_confidence': .99})
    if problem == 'vision_switch':nodes[master] = replace(nodes[master], extras={'checked': False, 'vision_confidence': .99})
    if problem == 'outside_row':nodes[master] = replace(nodes[master], bounds_in_screen=(800, 800, 1000, 900))
    normalized = normalize_accessibility_tree(tuple(nodes), screen_bounds=(0, 0, 1080, 2400))
    switch = next(n for n in normalized if n.node_id == 'master')
    assert '모노 오디오' not in switch.label_parts


@pytest.mark.parametrize('setting,package', [
    ('storage', 'com.samsung.android.lool'),
    ('storage', 'com.samsung.android.sm'),
    ('notification_sound', 'com.samsung.android.secsoundpicker'),
])
def test_expected_external_destination_does_not_restart_settings(setting, package):
    request = AgentTurnRequest('r', 's', raw_goal=None,
        device_context=DeviceContext('android', app_package=package),
        accessibility_tree=(AccessibilityNode('root', package_name=package),))
    environment = classify_environment(request)
    assert environment_recovery(environment, setting) == ('MANUAL_EXTERNAL_CHOICE', 'manual_choice')
    assert environment_recovery(environment, 'font_size') == ('OTHER_APP', 'open_settings')
