"""Empty discovery is a manual Bluetooth step, never a missing navigation target."""
from copy import deepcopy
import pytest
from tests.test_settings_execution import start, tree
from tests.test_visible_settings_navigation import screen

@pytest.mark.parametrize('populated', [False, True])
def test_bluetooth_connect_has_usable_instructions_without_reading_a_device_name(monkeypatch, populated):
    from app.services.screen_jev import JevScreenResolver
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER','jev')
    monkeypatch.setenv('TYPESAFE_API_KEY','synthetic-test-key')
    monkeypatch.setenv('JEV_MODEL','jev-1.14.0')
    def forbidden(*args, **kwargs): raise AssertionError('No model needed for the manual page')
    monkeypatch.setattr(JevScreenResolver,'resolve',forbidden)
    observation=screen('블루투스', [('device','Synthetic headphones','Synthetic identifier')] if populated else [])
    f,body=start('settings.bluetooth.connect',observation)
    guide=body['guide_command']
    assert guide['action_type']=='observe_only'
    assert guide['target_node_id'] is None
    assert guide['render_policy']['manual_handoff'] is True
    assert guide['render_policy']['allow_result_check'] is True
    assert '기기가 보이지 않으면' in guide['message']
    assert '페어링 모드' in guide['message']
    assert 'Synthetic' not in str(body)
    result=f.send('request_result')
    assert result['metadata']['task_outcome']['technical']['status']=='UNVERIFIABLE'

@pytest.mark.parametrize('goal,checked',[('settings.bluetooth.enable',True),('settings.bluetooth.disable',False)])
def test_no_devices_are_needed_to_verify_bluetooth_power(goal, checked):
    _,body=start(goal,tree(goal,checked))
    assert body['task_status']=='WAITING_RESULT'
    assert body['metadata']['task_outcome']['technical']['status']=='ALREADY_SET'

@pytest.mark.parametrize('title',['연결','설정'])
def test_bluetooth_menu_row_is_not_arrival_at_the_device_page(title):
    _,body=start('settings.bluetooth.connect',screen(title,[('bt','블루투스','')]))
    assert not body['guide_command']['render_policy'].get('manual_handoff')
    assert body['task_status'] not in {'COMPLETED','WAITING_RESULT'}


def test_reviewed_samsung_bluetooth_page_is_recognized_without_any_device_identity():
    import json
    from pathlib import Path
    observation=json.loads((Path(__file__).parent/'fixtures/expanded_settings/samsung-bluetooth-reviewed-page.json').read_text())
    _,body=start('settings.bluetooth.connect',observation)
    guide=body['guide_command']
    assert guide['render_policy']['manual_handoff'] is True
    assert guide['action_type']=='observe_only'
    assert guide['target_node_id'] is None
    assert body['task_status']=='IN_PROGRESS'
    assert '기기가 보이지 않으면' in guide['message']
    _,power=start('settings.bluetooth.enable',observation)
    assert power['metadata']['task_outcome']['technical']['status']=='ALREADY_SET'
