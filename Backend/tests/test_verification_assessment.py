"""Five-state evidence metadata survives the actual HTTP/checkpoint boundary."""
from app.main import create_app
from tests.helpers import authenticated_client
from tests.test_agent_sessions import payload
from tests.test_agent_turn_api import slider_tree


def test_waiting_user_and_settling_and_failed_have_distinct_http_evidence():
    client=authenticated_client(create_app())
    version=None
    def observe(at, event=None):
        nonlocal version
        data=payload('assessment-'+str(at),'assessment')
        if version is not None:
            data.pop('raw_goal');data['expected_version']=version
        data['accessibility_tree'].update(observation_id='o'+str(at),captured_at_ms=at)
        if event:data['user_action_event']=event
        response=client.post('/api/v1/agent/turn',json=data)
        assert response.status_code==200,response.text
        body=response.json();version=body['session_version']
        return body
    first=observe(1000)
    idle=observe(1100)
    info=idle['metadata']['verification']['assessment']
    assert info['status']=='WAITING' and info['waiting_for']=='user'
    assert not info['counts_as_failure']
    guide=first['guide_command']
    event={'event_id':'action','action_type':'tap_completed','occurred_at_ms':1200,
        'guide_command_id':guide['guide_command_id'],'target_node_id':guide['target_node_id']}
    settling=observe(1300,event)['metadata']['verification']['assessment']
    assert settling['status']=='WAITING' and settling['waiting_for']=='observation'
    failed=observe(1900)['metadata']['verification']['assessment']
    assert failed['status']=='FAILED' and failed['counts_as_failure']


def test_actual_font_scale_change_has_value_condition_and_still_requires_user_result():
    client=authenticated_client(create_app())
    def tree(progress,scale,at):
        return {**slider_tree(progress),'observation_id':'font-'+str(at),'captured_at_ms':at,
            'applied_settings':{'source':'android.provider.Settings.System.FONT_SCALE','font_scale':scale}}
    first=client.post('/api/v1/agent/turn',json={'request_id':'v1','session_id':'font-proof',
        'raw_goal':'increase font size','accessibility_tree':tree(3,1.0,1000)}).json()
    accepted=client.post('/api/v1/agent/turn',json={'request_id':'v2','session_id':'font-proof',
        'expected_version':first['session_version'],'accessibility_tree':tree(3,1.0,1100),
        'user_confirmation':'accepted','confirmation_id':first['metadata']['confirmation']['id']}).json()
    response=client.post('/api/v1/agent/turn',json={'request_id':'v3','session_id':'font-proof',
        'expected_version':accepted['session_version'],'accessibility_tree':tree(4,1.15,1800),
        'user_action_event':{'event_id':'drag-proof','action_type':'drag_completed','occurred_at_ms':1200,
            'guide_command_id':accepted['guide_command']['guide_command_id'],
            'target_node_id':'font-slider','target_bounds':[160,430,920,480]}})
    assert response.status_code==200,response.text
    body=response.json();assessment=body['metadata']['verification']['assessment']
    assert assessment['status']=='SUCCESS' and assessment['condition_type']=='value_changed'
    assert assessment['task_completed'] is True
    assert body['task_status']=='WAITING_RESULT'  # technical change != user satisfaction
    assert body['metadata']['verification']['model_review']['called'] is False
