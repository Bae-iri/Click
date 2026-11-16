"""Public nested settings menus must reach the real handoff boundary."""
import pytest
from ai_agent.task_graph.settings import DOCUMENT
from tests.test_settings_execution import start
from tests.test_visible_settings_navigation import screen
CASES=[]
for goal,c in DOCUMENT['goals'].items():
    if not c.get('graph_version') or c['mode']!='assisted':continue
    path=c.get('intermediate_path', [c['intermediate']] if c.get('intermediate') else [])
    headings=[head for head in [c['categories'],*path] if head]
    for index,head in enumerate(headings):
        destination=headings[index+1] if index+1<len(headings) else c['pages']
        CASES.append((goal,head[0],destination[0]))

@pytest.mark.parametrize('goal,title,label',CASES)
def test_nested_public_menu_does_not_handoff_on_the_parent(goal,title,label):
    _,b=start(goal,screen(title,[('next',label,None)]))
    assert not b['guide_command']['render_policy'].get('manual_handoff'),b
    assert b['guide_command']['target_node_id']=='next',b
    assert b['guide_command']['action_type']=='tap',b
    assert b['task_status']=='IN_PROGRESS',b

@pytest.mark.parametrize('goal',[g for g,c in DOCUMENT['goals'].items() if c.get('graph_version') and c['mode']=='assisted'])
def test_only_exact_safe_destination_enters_explicit_manual_handoff(goal):
    c=DOCUMENT['goals'][goal];_,b=start(goal,screen(c['pages'][0],[]))
    assert b['guide_command']['render_policy']['manual_handoff'] is True,b
    assert b['guide_command']['target_node_id'] is None
    assert b['metadata']['technical_outcome']['status']=='UNVERIFIABLE'

def test_private_notification_history_contents_remain_blocked_after_route_expansion():
    _,b=start('settings.notification_history.view',screen('알림 기록',[('private','메시지',None)]))
    assert b['guide_command']['action_type']=='observe_only'
    assert not b['guide_command']['render_policy'].get('manual_handoff')
    assert b['guide_command']['target_node_id'] is None

def test_saved_wifi_identity_named_settings_cannot_become_a_menu_target():
    t=screen('Wi-Fi',[('private','설정',None)])
    t['nodes'][-1]['resource_id']='com.android.settings:id/ssid'
    _,b=start('settings.wifi.saved_networks.view',t)
    assert b['guide_command']['target_node_id']!='private'
    assert not b['guide_command']['render_policy'].get('manual_handoff')
