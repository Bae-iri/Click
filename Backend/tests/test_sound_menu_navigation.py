"""A real filtered Samsung screen: sound-mode choices must not hide an offscreen Volume menu."""
from pathlib import Path
import json
import pytest
from ai_agent.runtime import ClickAgentEngine
from ai_agent.runtime.normalizer import normalize_accessibility_tree, infer_role
from ai_agent.runtime.screen_privacy import filter_nodes
from ai_agent.runtime.targeting import eligible_nodes
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext
from app.services.screen_jev import JevScreenResolver
from tests.test_screen_jev import scene


def captured_nodes():
    tree=json.loads((Path(__file__).parent/'fixtures/expanded_settings/samsung-sound-menu-with-hidden-volume.json').read_text())
    fields=AccessibilityNode.__dataclass_fields__
    raw=tuple(AccessibilityNode(**{k:tuple(v) if k=='bounds_in_screen' else v for k,v in node.items() if k in fields}) for node in tree['nodes'])
    return tree,filter_nodes(raw,'volume')


def test_hidden_volume_requests_list_search_without_sending_sound_mode_to_jev(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only-key');monkeypatch.setenv('JEV_MODEL','jev-1.14.0')
    graph,_,_,goal=scene('volume');tree,raw=captured_nodes()
    resolver=JevScreenResolver(lambda *a,**kw:pytest.fail('No menu target is visible; do not ask Jev to choose a sound mode'))
    engine=ClickAgentEngine(task_graphs={goal.task_id:graph},screen_resolver=resolver)
    result=engine.handle_turn(AgentTurnRequest(request_id='hidden-menu',session_id='s',episode_id='e',
        observation_id='native-sound-menu',captured_at_ms=1000,raw_goal=None,typed_goal=goal,device_context=DeviceContext('android'),
        active_window_id=tree['active_window_id'],screen_bounds=(0,0,tree['screen_width'],tree['screen_height']),accessibility_tree=raw))
    assert result.current_state_id=='S2_CATEGORY'
    assert result.guide_command.action_type.value=='observe_only'
    assert result.guide_command.render_policy['recovery']['reason_code']=='TARGET_NOT_VISIBLE'
    assert result.guide_command.render_policy['recovery']['next_action']=='manual_navigation'
    assert resolver.last_diagnostics['status']=='no_candidates'
    assert not resolver.last_diagnostics['called']


@pytest.mark.parametrize('resource',['low_button','mid_button','high_button'])
def test_plain_linear_layout_mode_choices_are_radio_controls_not_menus(resource):
    node=AccessibilityNode('mode',text='소리',package_name='com.android.settings',
        resource_id='com.android.settings:id/'+resource,class_name='android.widget.LinearLayout',clickable=True,
        bounds_in_screen=(20,200,300,500))
    assert infer_role(node)=='radio'


def test_a_real_settings_sound_menu_row_remains_navigable():
    graph,_,_,_=scene('volume')
    raw=(AccessibilityNode('row',text='소리',package_name='com.android.settings',class_name='android.widget.LinearLayout',
         clickable=True,bounds_in_screen=(20,200,900,350)),)
    nodes=normalize_accessibility_tree(raw,screen_bounds=(0,0,1080,2400))
    edge=next(t for t in graph.transitions if t.from_state=='S1_SETTINGS_HOME')
    assert [n.node_id for n in eligible_nodes(edge,nodes,graph)]==['row']


def test_missing_samsung_toolbar_does_not_send_volume_navigation_back_before_jev(monkeypatch):
    from tests.test_screen_jev import sender_for
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only-key')
    graph,_,_,goal=scene('volume')
    edge=next(t for t in graph.transitions if t.from_state=='S2_CATEGORY')
    resolver=JevScreenResolver(sender_for(edge))
    engine=ClickAgentEngine(task_graphs={goal.task_id:graph},screen_resolver=resolver)
    row=AccessibilityNode('volume',text='음량',package_name='com.android.settings',window_id=7,
        class_name='android.widget.TextView',clickable=True,bounds_in_screen=(20,300,900,400))
    result=engine.handle_turn(AgentTurnRequest(request_id='toolbar-redraw',session_id='s',
        typed_goal=goal,raw_goal=None,accessibility_tree=(row,),active_window_id=7,
        observation_id='fresh-volume',captured_at_ms=1000,screen_bounds=(0,0,1080,2400),
        device_context=DeviceContext('android',app_package='com.android.settings'),
        window_context={'settings_activity':'SUBPAGE','window_kind':'APPLICATION'}))
    assert resolver.last_diagnostics['called']
    assert result.guide_command.target_node_id=='volume'
    assert 'recovery_action' not in result.guide_command.render_policy
