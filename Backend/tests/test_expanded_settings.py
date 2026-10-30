"""Request -> prepared graph -> confirmed slider -> device evidence -> user result."""
import json
from pathlib import Path
import pytest
from tests.test_task_outcomes import Flow
from tests.test_visible_settings_navigation import screen as menu


def control(setting, value=40, at=1000, applied=None, extra_slider=False):
    package = 'com.android.settings'
    label = '밝기' if setting == 'brightness' else '미디어 음량'
    applied = value if applied is None else applied
    settings = {'source': 'android.provider.Settings.System.FONT_SCALE', 'font_scale': 1.0}
    if setting == 'brightness':
        settings.update(screen_brightness=applied, brightness_source='android.provider.Settings.System.SCREEN_BRIGHTNESS')
    else:
        settings.update(media_volume=applied, media_volume_source='android.media.AudioManager.STREAM_MUSIC')
    nodes = [dict(node_id='title', text='디스플레이' if setting == 'brightness' else '소리 및 진동',
                  class_name='android.widget.TextView', bounds_in_screen=[0, 60, 900, 180]),
             dict(node_id='target', text=label, resource_id='settings:id/'+setting,
                  class_name='android.widget.SeekBar', clickable=True,
                  bounds_in_screen=[50, 300, 1000, 410], extras={'progress': value, 'min': 0, 'max': 100})]
    if extra_slider:
        nodes.append(dict(node_id='other', text='알람 음량' if setting == 'volume' else '글자 크기',
            resource_id='settings:id/other', class_name='android.widget.SeekBar', clickable=True,
            bounds_in_screen=[50, 500, 1000, 610], extras={'progress': 40, 'min': 0, 'max': 100}))
    return dict(package_name=package, screen_width=1080, screen_height=2400,
        observation_id=f'o-{at}', captured_at_ms=at, active_window_id=42, applied_settings=settings,
        nodes=[{**node, 'package_name': package, 'window_id': 42} for node in nodes])


def begin(setting, direction='increase', value=40, extra_slider=False):
    flow = Flow()
    text = ('화면 밝기를 ' if setting == 'brightness' else '동영상과 음악의 미디어 음량을 ') + ('높여줘' if direction == 'increase' else '낮춰줘')
    ready = flow.resolve(text)
    assert ready['preparation']['status'] == 'ready', ready
    assert ready['goal']['goal_type'] == f'settings.{setting}.{direction}'
    assert ready['preparation']['graph_reference']['task_id'] == f'SET-{setting}-{direction}'
    body = flow.send('start_task', preparation_version='goal-inputs.v1', source_text=text,
        device_context={'platform': 'android', 'locale': 'ko-KR'},
        goal_resolution_id=ready['preparation']['id'], goal=ready['goal'],
        accessibility_tree=control(setting, value, extra_slider=extra_slider))
    return flow, body


@pytest.mark.parametrize('setting', ['brightness', 'volume'])
@pytest.mark.parametrize('direction', ['increase', 'decrease'])
def test_expanded_task_is_verified_and_followup_keeps_same_setting(setting, direction):
    flow, first = begin(setting, direction)
    assert first['task_status'] == 'WAITING_CONFIRMATION', first
    assert first['guide_command']['target_node_id'] == 'target'
    accepted = flow.send('respond_confirmation', user_confirmation='accepted',
        confirmation_id=first['metadata']['confirmation']['id'], accessibility_tree=control(setting, at=1100))
    assert accepted['guide_command']['action_type'] == 'drag'
    assert accepted['guide_command']['render_policy']['direction'] == ('right' if direction == 'increase' else 'left')
    assert 'automatable_setting' not in accepted['guide_command']['render_policy']
    changed = 60 if direction == 'increase' else 20
    event = flow.event()
    result = flow.send('observe', accessibility_tree=control(setting, changed, 1800), user_action_event=event)
    assert result['task_status'] == 'WAITING_RESULT', result
    assert result['metadata']['task_outcome']['technical']['status'] == 'CHANGED'
    assert '글자' not in result['metadata']['result_prompt']['message']
    label = '화면' if setting == 'brightness' else '소리'
    assert label in result['metadata']['result_prompt']['message']
    more = flow.answer('needs_help')
    assert all('글자' not in c['label'] for c in more['metadata']['follow_up']['choices'])
    follow = flow.send('adjust_task', adjustment_reason='too_large')
    assert follow['accepted_goal_type'] == f'settings.{setting}.decrease'
    assert follow['task_id'] == f'SET-{setting}-decrease'


@pytest.mark.parametrize('setting', ['brightness', 'volume'])
@pytest.mark.parametrize('problem', ['missing_setting', 'preview_only', 'wrong_target', 'no_action', 'wrong_direction'])
def test_new_tasks_never_complete_from_unrelated_or_preview_changes(setting, problem):
    flow, first = begin(setting)
    flow.send('respond_confirmation', user_confirmation='accepted',
        confirmation_id=first['metadata']['confirmation']['id'], accessibility_tree=control(setting, at=1100))
    tree = control(setting, 60, 1800, applied=40 if problem == 'preview_only' else 60)
    event = flow.event()
    if problem == 'missing_setting': tree.pop('applied_settings')
    if problem == 'wrong_target': event['target_node_id'] = 'other'
    if problem == 'wrong_direction': tree = control(setting, 20, 1800)
    kwargs = {'accessibility_tree': tree}
    if problem != 'no_action': kwargs['user_action_event'] = event
    body = flow.send('observe', **kwargs)
    assert body['metadata']['task_outcome']['technical']['status'] != 'CHANGED'
    assert body['task_status'] != 'COMPLETED'


@pytest.mark.parametrize('setting', ['brightness', 'volume'])
def test_multiple_sliders_target_only_requested_setting_and_limit_is_task_specific(setting):
    flow, body = begin(setting, value=100)
    assert body['guide_command']['action_type'] == 'observe_only'
    assert '글자' not in body['guide_command']['message']
    result = flow.send('request_result')
    assert '글자' not in result['metadata']['result_prompt']['message']
    other, first = begin(setting, extra_slider=True)
    result = other.send('respond_confirmation', user_confirmation='accepted',
        confirmation_id=first['metadata']['confirmation']['id'], accessibility_tree=control(setting, at=1100, extra_slider=True))
    assert result['guide_command']['target_node_id'] == 'target'


def test_missing_volume_kind_is_asked_and_unsupported_kind_never_falls_back_to_media():
    flow = Flow(); body = flow.resolve('소리 크기를 키워줘')
    assert body['preparation']['question']['field'] == 'audio_stream'
    assert body['goal'] is None
    body = flow.answer_input('other')
    assert body['preparation']['status'] == 'unsupported'
    assert body['goal'] is None
    explicit = Flow().resolve('알람 소리를 키워줘')
    assert explicit['preparation']['status'] == 'unsupported'


@pytest.mark.parametrize('text,family', [('밝기를 바꿔줘','brightness'), ('미디어 음량을 바꿔줘','volume')])
def test_missing_direction_uses_choices(text, family):
    flow = Flow(); body = flow.resolve(text)
    assert body['preparation']['family'] == family
    assert body['preparation']['question']['field'] == 'direction'
    assert flow.answer_input('decrease')['goal']['goal_type'] == f'settings.{family}.decrease'


@pytest.mark.parametrize('text', ['특정 앱 안에서만 밝기를 높여줘', '유튜브 앱에서 밝기를 높여줘', '이 앱에서만 미디어 음량을 키워줘'])
def test_app_local_requests_do_not_change_device_settings(text):
    result = Flow().resolve(text)
    assert result['preparation']['status'] == 'unsupported'
    assert result['goal'] is None


def test_named_video_app_still_selects_the_media_stream():
    result = Flow().resolve('유튜브 소리를 키워줘')
    assert result['goal']['goal_type'] == 'settings.volume.increase'
    assert result['preparation']['values']['audio_stream'] == 'media'


@pytest.mark.parametrize('setting,category', [('brightness','디스플레이'),('brightness','Display & touch'),
                                            ('volume','소리 및 진동'),('volume','Sound & vibration')])
def test_navigation_starts_with_the_correct_category(setting, category):
    flow = Flow(); ready = flow.resolve('화면 밝기 높여줘' if setting == 'brightness' else '미디어 음량 높여줘')
    body = flow.send('start_task', preparation_version='goal-inputs.v1', source_text=flow.source_text,
        device_context={'platform': 'android', 'locale': 'ko-KR'},
        goal_resolution_id=ready['preparation']['id'], goal=ready['goal'],
        accessibility_tree=menu('설정', [('wanted',category,category),('other','접근성','접근성')]))
    assert body['guide_command']['target_node_id'] == 'wanted', body


def native(name, at):
    # Captured by NativeSettingsSnapshotTest on AOSP, with font scale 1.5.
    tree = json.loads((Path(__file__).parent / 'fixtures/expanded_settings' / (name + '.json')).read_text())
    tree.update(captured_at_ms=at, observation_id=f'native-{name}-{at}')
    return tree


def start_native(flow, setting, tree):
    ready = flow.resolve('화면을 밝게 해줘' if setting == 'brightness' else '미디어 음량을 키워줘')
    return flow.send('start_task', preparation_version='goal-inputs.v1', source_text=flow.source_text,
        goal_resolution_id=ready['preparation']['id'], goal=ready['goal'],
        device_context={'platform': 'android', 'locale': 'en-US'}, accessibility_tree=tree)


def test_actual_aosp_brightness_menu_opens_systemui_control_and_accepts_confirmation():
    flow = Flow(); tree = native('brightness', 1000)
    first = start_native(flow, 'brightness', tree)
    target = next(n for n in tree['nodes'] if n['node_id'] == first['guide_command']['target_node_id'])
    assert target['clickable']
    assert any(n['parent_id'] == target['node_id'] and n.get('text') == 'Brightness level' for n in tree['nodes'])
    first = flow.send('observe', accessibility_tree=native('brightness-control', 1500))
    assert first['task_status'] == 'WAITING_CONFIRMATION', first
    assert first['state_id'] == 'S3_CONTROL'
    accepted = flow.send('respond_confirmation', user_confirmation='accepted',
        confirmation_id=first['metadata']['confirmation']['id'], accessibility_tree=native('brightness-control', 1600))
    assert accepted['guide_command']['action_type'] == 'drag'
    assert accepted['guide_command']['target_node_id'] == 'node_0_0_0_0_0_0_0'


def test_actual_aosp_volume_selects_media_range_among_six_streams():
    flow = Flow(); tree = native('volume', 1000)
    first = start_native(flow, 'volume', tree)
    assert first['task_status'] == 'WAITING_CONFIRMATION', first
    target = next(n for n in tree['nodes'] if n['node_id'] == first['guide_command']['target_node_id'])
    assert target['content_description'] == 'Media volume,'
    assert target['extras'] == {'progress': 5, 'min': 0, 'max': 15}
    accepted = flow.send('respond_confirmation', user_confirmation='accepted',
        confirmation_id=first['metadata']['confirmation']['id'], accessibility_tree=native('volume', 1100))
    assert accepted['guide_command']['action_type'] == 'drag'
    changed = flow.send('observe', accessibility_tree=native('volume-changed', 1800), user_action_event=flow.event())
    assert changed['task_status'] == 'WAITING_RESULT', changed
    assert changed['metadata']['task_outcome']['technical']['status'] == 'CHANGED'
    assert changed['metadata']['task_outcome']['technical']['setting_value'] == 6
