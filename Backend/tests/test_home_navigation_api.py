from app.main import create_app
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload
from tests.test_visible_settings_navigation import screen


def test_home_observation_resets_route_without_replacing_goal():
    app=create_app()
    with authenticated_client(app) as client:
        payload=start_payload()
        payload['accessibility_tree']=screen('설정',[('display','디스플레이','디스플레이'),('access','접근성','접근성')])
        payload['accessibility_tree'].update(captured_at_ms=1000,observation_id='before-navigation')
        first=client.post('/api/v1/agent/commands',json=payload)
        assert first.status_code==200,first.text
        before=first.json()
        body={'contract_version':payload['contract_version'],'command_type':'observe',
              'request_id':'observed-home','session_id':payload['session_id'],
              'expected_version':before['session_version'],'episode_id':before['episode_id'],
              'accessibility_tree':{'package_name':'com.android.launcher3','screen_width':1080,'screen_height':2400,
                  'captured_at_ms':2000,'observation_id':'home-navigation',
                  'nodes':[{'node_id':'settings','text':'설정','package_name':'com.android.launcher3',
                            'clickable':True,'bounds_in_screen':[0,0,100,100]}]},
              'user_action_event':{'event_id':'home-event','action_type':'home_navigated','occurred_at_ms':1500}}
        result=client.post('/api/v1/agent/commands',json=body)
        assert result.status_code==200,result.text
        after=result.json()
        assert after['accepted_goal_type']==before['accepted_goal_type']
        assert after['episode_id']==before['episode_id']
        assert after['state_id']=='S0_UNKNOWN'
        assert after['task_status']=='IN_PROGRESS'
        assert after['step']==0
        assert all(row['status']!='completed' for row in after['metadata']['checklist'])
