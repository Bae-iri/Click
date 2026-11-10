"""Intermediate routes explain the next step without weakening abstention or consent."""
import pytest
from app.task_graphs import approved_graphs
from app.services.navigation_context import next_menu_context
from app.services.screen_jev import JevScreenResolver
from tests.test_screen_jev import sender_for
from ai_agent.runtime.types import TypedGoal, AccessibilityNode
from ai_agent.runtime.normalizer import normalize_accessibility_tree

@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only-key');monkeypatch.setenv('JEV_MODEL','jev-1.14.0')

@pytest.mark.parametrize('goal',['settings.vpn.connect','settings.vpn.disconnect','settings.private_dns.change'])
def test_intermediate_route_context(goal):
    g=approved_graphs()[goal];c=next_menu_context(next(e for e in g.transitions if e.transition_id=='OPEN_INTERMEDIATE'),g)
    assert c['scope']=='open_next_menu_only' and c['completes_goal'] is False
    assert c['current_page_titles'][0][0]=='연결'
    assert c['next_page_titles'][0][0]=='기타 연결 설정'
    assert c['remaining_page_titles'][-1][0][0] in {'VPN','프라이빗 DNS'}

def test_route_stops_before_confirmation():
    g=approved_graphs()['settings.display_size.increase']
    c=next_menu_context(next(e for e in g.transitions if e.transition_id=='OPEN_CATEGORY'),g)
    assert c['remaining_page_titles'][-1][0][0]=='화면 크게/작게'
    assert 'S_CHANGED' not in str(c)
    assert next_menu_context(next(e for e in g.transitions if e.completes_task),g) is None

@pytest.mark.parametrize('confidence',[1.0,0.4])
def test_real_connections_geometry_preserves_model_abstention(confidence):
    g=approved_graphs()['settings.vpn.connect'];e=next(t for t in g.transitions if t.transition_id=='OPEN_INTERMEDIATE')
    def n(id,**kw):return AccessibilityNode(id,package_name='com.android.settings',window_id=42,**kw)
    raw=(n('private-title',text='연결',class_name='android.widget.TextView',bounds_in_screen=(158,187,264,267)),
        n('private-list',scrollable=True,class_name='androidx.recyclerview.widget.RecyclerView',bounds_in_screen=(0,300,1080,2200)),
        n('private-row',parent_id='private-list',clickable=True,class_name='android.widget.LinearLayout',bounds_in_screen=(0,1330,1080,1500)),
        n('private-label',parent_id='private-row',text='기타 연결 설정',resource_id='android:id/title',class_name='android.widget.TextView',bounds_in_screen=(68,1368,378,1433)))
    typed=TypedGoal(g.definition.goal_type,g.definition.target_app,g.definition.task_type,g.definition.required_slots,'',1,task_id=g.definition.task_id,is_supported=True,required_slots_filled=True)
    def send(payload,**kwargs):
        candidates=[c for c in payload['questions']['screen']['criteria'].values() if isinstance(c,dict)]
        assert len(candidates)==1;c=candidates[0]
        assert c['transition']==e.transition_id and c['navigation']['route_description']=='연결 → 기타 연결 설정 → VPN'
        assert 'not whether the final goal control is visible' in c['description']
        assert 'private-' not in str(payload)
        assert payload['state']['immediate_request']['open_menu'][0]=='기타 연결 설정'
        assert payload['state']['goal']['type']=='settings.vpn.connect'
        return sender_for(e,mutate=lambda r:r['answers']['screen'].update(confidence=confidence))(payload,**kwargs)
    resolver=JevScreenResolver(send);selected=resolver.resolve(nodes=normalize_accessibility_tree(raw),raw_nodes=raw,graph=g,goal=typed)
    if confidence<0.8:assert selected is None and resolver.last_diagnostics['status']=='low_confidence'
    else:assert selected.target_node_id=='private-row'


def test_all_approved_menu_routes_are_finite_and_stop_before_value_actions():
    for graph in approved_graphs().values():
        for edge in graph.transitions:
            context=next_menu_context(edge,graph)
            if context:
                assert context['completes_goal'] is False
                assert len(context['remaining_page_titles'])<=6
                assert 'source_text' not in context and 'coordinates' not in context
                assert not edge.completes_task and not edge.confirmation_message


def test_immediate_request_requires_one_menu_and_an_independent_matching_title():
    from app.services.navigation_context import immediate_menu_request
    from ai_agent.runtime.types import NormalizedNode
    g=approved_graphs()['settings.vpn.connect'];e=next(t for t in g.transitions if t.transition_id=='OPEN_INTERMEDIATE')
    unrelated=NormalizedNode('title','디스플레이','text',bounds=(0,0,100,50),package_name='com.android.settings')
    assert immediate_menu_request({'one':(e,None)},(unrelated,),g) is None
    assert immediate_menu_request({'one':(e,None),'two':(e,None)},(),g) is None
    mutation=next(t for t in approved_graphs()['settings.wifi.enable'].transitions if t.completes_task)
    assert immediate_menu_request({'one':(mutation,None)},(),approved_graphs()['settings.wifi.enable']) is None
