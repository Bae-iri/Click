"""Exercise the reported settings requests through preparation and native routing."""
from unittest.mock import Mock

import pytest

from app.schemas.agent_turn import TypedGoalRequest
from tests.test_settings_execution import start, tree
from tests.test_task_outcomes import Flow
from tests.test_visible_settings_navigation import screen


@pytest.mark.parametrize('text,goal', [
    ('배터리 잔량 표시해줘', 'settings.battery_percentage.enable'),
    ('배터리 백분율 켜줘', 'settings.battery_percentage.enable'),
    ('배터리 잔량 표시 꺼줘', 'settings.battery_percentage.disable'),
    ('기본 키보드 설정', 'settings.keyboard.change'),
    ('키보드 바꾸고 싶어', 'settings.keyboard.change'),
    ('잠금 화면의 알림 설정', 'settings.lockscreen_notifications.manage'),
    ('테마 바꿔줘', 'settings.theme.change'),
    ('갤럭시 테마 적용해줘', 'settings.theme.change'),
    ('다크모드 켜줘', 'settings.dark_mode.enable'),
    ('어두운 테마 켜줘', 'settings.dark_mode.enable'),
])
def test_reported_settings_requests_prepare_without_a_model(text, goal):
    flow = Flow()
    flow.app.state.turn_service.goal_preparation.llm = Mock()
    flow.app.state.turn_service.goal_preparation.llm.interpret_inputs.side_effect = AssertionError('Explicit request')
    body = flow.resolve(text)
    assert body['goal'] and body['goal']['goal_type'] == goal, body
    assert body['preparation']['status'] == 'ready', body
    TypedGoalRequest.model_validate(body['goal'])


@pytest.mark.parametrize('goal,title,next_label', [
    ('settings.battery_percentage.enable', '설정', '알림'),
    ('settings.battery_percentage.enable', '알림', '고급 설정'),
    ('settings.keyboard.change', '일반', '언어 및 입력'),
    ('settings.keyboard.change', '일반', '키보드 목록 및 기본 설정'),
    ('settings.keyboard.change', '언어 및 입력', '화면 키보드'),
    ('settings.keyboard.change', '키보드 목록 및 기본 설정', '기본 키보드'),
    ('settings.lockscreen_notifications.manage', '알림', '잠금 화면 알림'),
    ('settings.lockscreen_notifications.manage', '잠금화면 및 AOD', '잠금화면 알림'),
    ('settings.theme.change', '설정', '테마'),
])
def test_public_settings_route_continues_to_the_destination(goal, title, next_label):
    _, body = start(goal, screen(title, [('next', next_label, None)]))
    assert body['task_status'] == 'IN_PROGRESS', body
    assert body['guide_command']['action_type'] == 'tap', body
    assert body['guide_command']['target_node_id'] == 'next', body
    assert not body['guide_command']['render_policy'].get('manual_handoff'), body


@pytest.mark.parametrize('version', ['2.4.0', '2.4.1'])
def test_coupang_goals_remain_compatible_during_the_catalog_upgrade(version):
    TypedGoalRequest(catalog_version=version, goal_type='coupang.cart.view',
        target_app='coupang', slots={'action': 'view'})


@pytest.mark.parametrize('version', ['1.0.0', '2.0.0', '2.4.0'])
def test_theme_change_requires_the_new_catalog(version):
    with pytest.raises(ValueError, match='2.4.1'):
        TypedGoalRequest(catalog_version=version, goal_type='settings.theme.change',
            target_app='android_settings', slots={'setting': 'theme', 'action': 'change'})


@pytest.mark.parametrize('slots', [{}, {'setting': 'theme', 'action': 'apply'},
    {'setting': 'theme', 'action': 'change', 'theme_name': 'private choice'}])
def test_theme_selection_stays_out_of_typed_goal_slots(slots):
    with pytest.raises(ValueError):
        TypedGoalRequest(catalog_version='2.4.1', goal_type='settings.theme.change',
            target_app='android_settings', slots=slots)


@pytest.mark.parametrize('goal,title', [
    ('settings.mono_audio.enable', '청각 보조'),
    ('settings.mono_audio.disable', '오디오 조정'),
    ('settings.battery_percentage.enable', '고급 설정'),
])
@pytest.mark.parametrize('checked', [False, True])
def test_shared_settings_page_cannot_identify_a_lone_unlabelled_switch(goal, title, checked):
    observation = tree(goal, checked)
    observation['nodes'][1]['text'] = title
    observation['nodes'][-1]['text'] = 'On' if checked else 'Off'
    _, body = start(goal, observation)
    assert body['task_status'] not in {'WAITING_CONFIRMATION', 'WAITING_RESULT', 'COMPLETED'}, body
    assert body['guide_command']['action_type'] == 'observe_only', body


def test_generic_master_switch_is_still_allowed_on_a_dedicated_mono_audio_page():
    goal = 'settings.mono_audio.enable'
    observation = tree(goal, False)
    observation['nodes'][-1]['text'] = 'Off'
    _, body = start(goal, observation)
    assert body['task_status'] == 'WAITING_CONFIRMATION', body
    assert body['guide_command']['target_node_id'] == 'master', body


@pytest.mark.parametrize('text', ['심카드 추가', '심카드 등록'])
@pytest.mark.parametrize('foreground', [None, 'com.android.settings', 'com.coupang.mobile'])
def test_foreground_commerce_app_cannot_make_explicit_sim_requests_ambiguous(text, foreground):
    from app.services.goal_resolution import GoalResolutionService
    request = Mock(source_text=text, request_id='sim-regression', session_id='sim-regression',
        device_context=Mock(foreground_package=foreground))
    body = GoalResolutionService(Mock(), Mock()).resolve(request, 0)
    assert body.resolution_status == 'confirmation_required', body
    assert body.goal.goal_type == 'settings.sim.manage', body


@pytest.mark.parametrize('text', ['어두운 테마로 바꿔줘', '밝은 테마로 바꿔줘',
    '다크모드 테마 바꿔줘', '라이트모드 테마 바꿔줘', '검은 배경 테마 바꿔줘', '흰 바탕 테마 바꿔줘'])
def test_display_mode_requests_cannot_select_galaxy_themes(text):
    from app.services.goal_preparation import GoalPreparationService
    from app.services.goal_resolution import GoalResolutionService
    preparation = GoalPreparationService(Mock(), Mock(), Mock(), llm=Mock())
    assert all(g['goal_type'] != 'settings.theme.change' for g in preparation._keywords(text))
    classifier = Mock()
    classifier.classify.return_value = []
    request = Mock(source_text=text, request_id='theme-regression', session_id='theme-regression',
        device_context=Mock(foreground_package='com.android.settings'))
    body = GoalResolutionService(Mock(), classifier).resolve(request, 0)
    assert body.goal is None or body.goal.goal_type != 'settings.theme.change', body
