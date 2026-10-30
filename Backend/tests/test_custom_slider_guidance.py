from app.main import create_app
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload


def test_generic_accessibility_slider_reaches_concrete_font_adjustment_confirmation():
    payload=start_payload()
    package='com.android.settings'
    payload['accessibility_tree']={'package_name':package,'screen_width':1080,'screen_height':2400,'nodes':[
        {'node_id':'title','text':'글자 크기','class_name':'android.widget.TextView','package_name':package,
         'bounds_in_screen':[0,0,1080,100]},
        {'node_id':'size_control','role':'slider','class_name':'android.view.View','package_name':package,
         'bounds_in_screen':[100,400,900,500],'extras':{'progress':2,'min':0,'max':7}}]}
    with authenticated_client(create_app()) as client:
        response=client.post('/api/v1/agent/commands',json=payload)
    assert response.status_code==200,response.text
    body=response.json()
    assert body['state_id']=='S4_FONT_SIZE_CONTROL'
    assert body['guide_command']['command_type']=='confirmation'
    assert body['guide_command']['proposed_action']=='drag'
    assert body['guide_command']['target_node_id']=='size_control'
