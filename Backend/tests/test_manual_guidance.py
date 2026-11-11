"""Task purpose remains visible when execution hands sensitive choices back to the user."""
import pytest
from ai_agent.task_graph.assisted_guidance import guidance,privacy_message
from ai_agent.task_graph.settings import DOCUMENT
from tests.test_settings_execution import prepare,start
from tests.test_visible_settings_navigation import screen
GOALS=[g for g,c in DOCUMENT['goals'].items() if c['mode']=='assisted']

@pytest.mark.parametrize('goal',GOALS)
def test_every_manual_task_names_the_request_and_destination_before_start(goal):
    _,ready=prepare(goal);c=DOCUMENT['goals'][goal]
    assert c['name'] in ready['display_name']
    if not guidance(goal).get('privacy_notice'):
        assert c['name'] in ready['message']
    assert c['page_name'] in ready['message']
    assert '직접' in ready['message']

@pytest.mark.parametrize('goal',['settings.vpn.connect','settings.vpn.disconnect','settings.wifi.connect','settings.wifi.share','settings.app_data.clear','settings.screen_lock.change'])
def test_protected_input_keeps_task_specific_next_steps_and_stays_blocked(goal):
    t=screen('VPN',[])
    t['nodes'].append(dict(node_id='secret',package_name='com.android.settings',class_name='android.widget.EditText',role='text_input',text='secret-test-content',extras={'password':True},bounds_in_screen=[50,300,500,400]))
    _,body=start(goal,t);command=body['guide_command']
    assert command['command_type']=='blocked' and command['action_type']=='observe_only'
    assert command['target_node_id'] is None
    assert DOCUMENT['goals'][goal]['name'] in command['message']
    assert guidance(goal)['manual'] in command['message']
    assert 'secret-test-content' not in str(body)

@pytest.mark.parametrize('goal,word',[('settings.vpn.connect','연결된'),('settings.vpn.disconnect','해제된')])
def test_vpn_handoff_explains_next_step_and_result_is_only_user_report(goal,word):
    f,body=start(goal,screen('VPN',[]));guide=body['guide_command']
    assert guide['render_policy']['manual_handoff'] is True
    assert 'VPN' in guide['message'] and '프로필' in guide['message']
    assert '화면이에요' in guide['message']
    assert body['metadata']['technical_outcome']['status']=='UNVERIFIABLE'
    body=f.send('request_result');assert word in body['metadata']['result_prompt']['message']
    assert f.answer('satisfied')['metadata']['task_outcome']['final']=='USER_REPORTED_RESOLVED'


def test_unknown_or_automatic_tasks_never_inherit_manual_private_instructions():
    for goal in ['settings.wifi.enable','settings.font_size.increase','arbitrary.goal',None]:
        assert privacy_message(goal,'base')=='base'
    assert '연결 해제' not in guidance('settings.vpn.connect')['manual']
    assert '비밀번호' not in guidance('settings.vpn.disconnect')['manual']


@pytest.mark.parametrize('goal',['settings.vpn.connect','settings.wifi.connect','settings.screen_lock.change','settings.notification_history.view','settings.hotspot.password.change'])
def test_privacy_pause_is_explained_before_the_sensitive_step(goal):
    _,ready=prepare(goal);message=ready['message']
    expected = (f"{DOCUMENT['goals'][goal]['page_name']} 설정 화면으로 안내할게요. "
        '다만 개인정보 보호를 위해 민감한 단계에서는 멈출게요! '
        '그때는 Test User님께서 직접 진행 부탁드려요!')
    assert message.startswith(expected)
    assert guidance(goal)['privacy_stage'] not in message
    assert ready['metadata']['goal_selection']['message'] == message

@pytest.mark.parametrize('goal',['settings.screen_timeout.set','settings.language.change','settings.ringtone.change','settings.software_update.check'])
def test_nonprivate_manual_choices_do_not_get_a_personal_data_warning(goal):
    _,ready=prepare(goal)
    assert '개인정보' not in ready['message'] and '다만,' not in ready['message']
    assert '직접' in ready['message']


@pytest.mark.parametrize('name,recipient', [('  배정윤  ', '배정윤님께서 '), (None, '')])
def test_short_sensitive_intro_uses_the_authenticated_profile_without_placeholder(name, recipient):
    from sqlalchemy import select
    from app.models.user import User
    from tests.test_task_outcomes import Flow
    flow = Flow()
    with flow.app.state.session_factory() as db:
        db.scalar(select(User)).name = name
        db.commit()
    ready = flow.resolve('VPN 연결해줘')
    assert ready['preparation']['status'] == 'ready'
    assert ready['goal']['goal_type'] == 'settings.vpn.connect'
    assert ready['message'] == ('VPN 설정 화면으로 안내할게요. '
        '다만 개인정보 보호를 위해 민감한 단계에서는 멈출게요! '
        f'그때는 {recipient}직접 진행 부탁드려요!')
    assert ready['metadata']['goal_selection']['message'] == ready['message']
    assert '00님' not in ready['message']
