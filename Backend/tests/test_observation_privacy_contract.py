"""Shared native/backend fixtures, provider-neutral protection and diagnosis."""
from dataclasses import replace
import json
from pathlib import Path
from unittest.mock import Mock
import pytest
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext
from ai_agent.runtime.screen_privacy import assess_screen, filter_nodes, private_node_ids, minimize_nodes
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime import ClickAgentEngine
from ai_agent.telemetry.trace_logger import diagnostic_payload
from app.services.screen_jev import JevScreenResolver
from tests.test_screen_jev import scene, sender_for

CASES=json.loads((Path(__file__).parents[1]/'docs/fixtures/observation-privacy-cases.json').read_text())

@pytest.mark.parametrize('case',CASES,ids=lambda c:c['name'])
def test_native_and_runtime_share_the_same_privacy_decisions(case):
    nodes=tuple(AccessibilityNode(**{**n,'bounds_in_screen':tuple(n['bounds_in_screen'])}) for n in case['nodes'])
    result=assess_screen(nodes,case['setting'])
    expected={'allowed':None,'sensitive_input':'sensitive_screen','sensitive_screen':'sensitive_screen'}[case['expected']]
    assert result.status==expected
    if case['expected']=='allowed':
        safe=filter_nodes(nodes,case['setting'])
        for node in safe:
            if node.node_id.startswith('private'):
                assert node.text is None and not node.clickable and not node.enabled
        assert any(n.node_id=='control' and n.enabled for n in safe)
        if case['name'].endswith('unlabelled_native_control_survives'):
            assert any(n.node_id=='control' for n in minimize_nodes(normalize_accessibility_tree(safe),case['setting']))
    else:
        assert result.reason in {'input_present','credential_input','protected_screen'}

@pytest.mark.parametrize('setting',['font_size','brightness','volume'])
def test_private_rows_never_enter_provider_choices_even_when_their_names_are_allowlisted(setting,monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY','synthetic-test-key')
    graph,transition,node,goal=scene(setting)
    contract=next(c for c in CASES if c['name']==setting+'_private_identity_is_not_a_screen_title')
    raw=tuple(AccessibilityNode(**{**n,'bounds_in_screen':tuple(n['bounds_in_screen'])}) for n in contract['nodes'])
    # Bypass runtime preparation deliberately: resolver must enforce this too.
    sent=[]
    def sender(payload,**kwargs):sent.append(payload);return sender_for(transition)(payload,**kwargs)
    resolver=JevScreenResolver(sender)
    resolver.resolve(nodes=normalize_accessibility_tree(raw),raw_nodes=raw,graph=graph,goal=goal)
    assert sent, resolver.last_diagnostics
    hidden=private_node_ids(raw)
    assert hidden
    # Normalized private identities must never be exported or become controls.
    for payload in sent:
        assert '비밀번호' not in json.dumps(payload,ensure_ascii=False)
        assert len(payload['state']['nodes']) <= 3
    assert not any(n.actionable for n in normalize_accessibility_tree(filter_nodes(raw,setting)) if n.node_id in hidden)

@pytest.mark.parametrize('provider',['rules','jev'])
def test_credential_boundary_applies_to_every_agent_selector(provider,monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY','synthetic-test-key')
    graph,_,_,goal=scene('font_size');sender=Mock(side_effect=AssertionError('No provider call on an input screen'))
    resolver=JevScreenResolver(sender) if provider=='jev' else None
    nodes=(AccessibilityNode('title',text='글자 크기',package_name='com.android.settings',bounds_in_screen=(0,0,1000,100)),
        AccessibilityNode('password',role='text_input',package_name='com.android.settings',bounds_in_screen=(0,200,1000,300),extras={'is_password':True}))
    engine=ClickAgentEngine(task_graphs={goal.task_id:graph},screen_resolver=resolver)
    response=engine.handle_turn(AgentTurnRequest('r','s',None,nodes,typed_goal=goal,device_context=DeviceContext('android')))
    assert response.guide_command.render_policy['observation_reason']=='credential_input'
    assert response.guide_command.target_node_id is None
    sender.assert_not_called()


def test_unknown_screen_is_not_claimed_to_contain_personal_information():
    node=AccessibilityNode('unknown',text='제조사의 다른 화면',package_name='com.android.settings')
    result=assess_screen((node,),'wifi')
    assert result.reason=='unrecognized_settings_screen'
    assert '개인정보' not in result.message and '비밀번호' not in result.message and '팝업' not in result.message
    safe=diagnostic_payload({'ui_selection':{'provider':'jev','called':False,'status':result.status,'observation_reason':result.reason,'text':'SECRET'}})
    assert safe['ui_selection']['observation_reason']==result.reason
    assert 'SECRET' not in str(safe)


def test_public_version_exposes_only_the_observation_policy_identity():
    from app.main import create_app
    from fastapi.testclient import TestClient
    from ai_agent.runtime.screen_privacy import observation_contract
    result=TestClient(create_app()).get('/version').json()['observation_contract']
    assert result==observation_contract()
    assert set(result)=={'schema_version','policy_sha256','vocabulary_sha256'}
    assert len(result['policy_sha256'])==64 and len(result['vocabulary_sha256'])==64


@pytest.mark.parametrize('checked',[False,True,None])
def test_selector_receives_explicit_native_state_instead_of_guessing_from_selected(checked,monkeypatch):
    from app.task_graphs import approved_graphs
    from ai_agent.runtime.types import TypedGoal
    monkeypatch.setenv('TYPESAFE_API_KEY','synthetic-test-key')
    graph=approved_graphs()['settings.bluetooth.enable']
    edge=next(t for t in graph.transitions if t.verification_predicate=='target_checked_on')
    raw=(AccessibilityNode('title',text='블루투스',package_name='com.android.settings',role='text',bounds_in_screen=(0,0,1000,100)),
        AccessibilityNode('master',text='블루투스',package_name='com.android.settings',role='switch',class_name='android.widget.Switch',
            checkable=True,selected=True,clickable=True,resource_id='com.android.settings:id/switch_widget',bounds_in_screen=(0,200,1000,300),
            extras={} if checked is None else {'checked':checked}))
    goal=TypedGoal(graph.definition.goal_type,'android_settings','settings_change',graph.definition.required_slots,'',1,
        task_id=graph.definition.task_id,is_supported=True,required_slots_filled=True)
    sent=[]
    def sender(payload,**kwargs):sent.append(payload);return sender_for(edge)(payload,**kwargs)
    JevScreenResolver(sender).resolve(nodes=normalize_accessibility_tree(raw),raw_nodes=raw,graph=graph,goal=goal)
    if checked is None:
        assert not sent # No native proof means no switch candidate, never unchecked=False.
    else:
        assert sent and sent[0]['state']['goal']['slots']['state']=='on'
        switches=[n for n in sent[0]['state']['nodes'] if n['role']=='toggle']
        assert len(switches)==1 and switches[0]['checked'] is checked
