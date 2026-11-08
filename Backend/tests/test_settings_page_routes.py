"""Read-only page navigation, native heading verification and privacy regressions."""
import json
from copy import deepcopy
from pathlib import Path
import pytest
from pydantic import ValidationError
from app.schemas.agent_turn import TypedGoalRequest
from app.task_graphs.settings_navigation import PAGES, navigation_releases
from app.task_graphs.validation import load_graph
from tests.test_task_outcomes import Flow
from tests.test_visible_settings_navigation import screen


def tree(title, rows=(), at=1000):
    result=screen(title, rows)
    result.update(observation_id='route-'+str(at),captured_at_ms=at)
    return result


def begin(page, initial):
    flow=Flow()
    text=page['name']+' 설정 열어줘'
    ready=flow.resolve(text)
    assert ready['preparation']['status']=='ready', ready
    assert ready['goal']['goal_type']=='settings.navigation.'+page['key'],ready
    started=flow.send('start_task',preparation_version='goal-inputs.v1',source_text=text,
        goal_resolution_id=ready['preparation']['id'],goal=ready['goal'],
        device_context={'platform':'android','locale':'ko-KR'},accessibility_tree=initial)
    return flow,started


@pytest.mark.parametrize('page',PAGES,ids=lambda p:p['key'])
def test_page_opens_from_home_and_completes_only_on_native_heading(page):
    flow,first=begin(page,tree('설정',[('destination',page['titles'][0],None)]))
    if page['key']=='settings':
        result=first
    else:
        assert first['task_status']!='COMPLETED',first
        assert first['guide_command']['target_node_id']=='destination',first
        assert first['guide_command']['action_type']=='tap'
        result=flow.send('observe',accessibility_tree=tree(page['titles'][0],at=2000))
    assert result['task_status']=='COMPLETED',result
    assert result['metadata']['task_outcome']['technical']['status']=='OPENED'
    assert result['guide_command']['action_type']=='observe_only'
    assert '화면을 열었어요' in result['guide_command']['message']
    assert not result['metadata'].get('result_prompt')


@pytest.mark.parametrize('page',[p for p in PAGES if p['key']!='settings'],ids=lambda p:p['key'])
def test_destination_label_in_menu_does_not_prove_arrival(page):
    flow,first=begin(page,tree('설정',[('destination',page['titles'][0],None)]))
    assert first['metadata']['task_outcome']['technical']['status']!='OPENED'
    result=flow.send('observe',accessibility_tree=tree('설정',[('destination',page['titles'][0],None)],at=2000))
    assert result['task_status']!='COMPLETED'


@pytest.mark.parametrize('page',[p for p in PAGES if p['categories']],ids=lambda p:p['key'])
def test_category_route_has_a_second_navigation_step(page):
    flow,first=begin(page,tree('설정',[('category',page['categories'][0],None)]))
    assert first['guide_command']['target_node_id']=='category',first
    second=flow.send('observe',accessibility_tree=tree(page['categories'][0],[('destination',page['titles'][0],None)],at=2000))
    assert second['task_status']!='COMPLETED',second
    assert second['guide_command']['target_node_id']=='destination',second


@pytest.mark.parametrize('key',['wifi','bluetooth','talkback'])
def test_sensitive_input_blocks_page_completion(key):
    page=next(p for p in PAGES if p['key']==key)
    private=tree(page['titles'][0])
    private['nodes'].append(dict(node_id='secret',package_name='com.android.settings',
        class_name='android.widget.EditText',text='private',bounds_in_screen=[0,300,500,400],extras={'editable':True}))
    flow,body=begin(page,private)
    assert body['task_status']!='COMPLETED'
    assert body['metadata']['task_outcome']['technical']['status']!='OPENED'


def test_unknown_page_slots_and_old_catalog_are_rejected():
    base=dict(catalog_version='2.2.0',goal_type='settings.navigation.wifi',target_app='android_settings',
        slots={'setting':'navigation_wifi','action':'open'})
    TypedGoalRequest(**base)
    for change in [{'catalog_version':'2.1.0'},{'slots':{'setting':'navigation_bluetooth','action':'open'}},
                   {'slots':{'setting':'navigation_wifi','action':'open','ssid':'private'}}]:
        with pytest.raises(ValidationError): TypedGoalRequest(**{**base,**change})


def test_navigation_predicate_cannot_approve_mutation_graph():
    _,graph=next(navigation_releases())
    body=json.loads(json.dumps(graph.to_dict()))
    body['definition']['task_type']='settings_change'
    with pytest.raises(ValueError,match='read-only'): load_graph(body)


@pytest.mark.parametrize('text',['블루투스 꺼줘','톡백 켜줘','다크모드 켜줘'])
def test_change_intent_is_never_replaced_with_open_page(text):
    body=Flow().resolve(text)
    assert body['goal']['goal_type'] in {'settings.bluetooth.disable','settings.talkback.enable','settings.dark_mode.enable'}
    assert body['preparation']['status']=='ready'


@pytest.mark.parametrize('page',[p for p in PAGES if p['intermediate']],ids=lambda p:p['key'])
def test_accessibility_intermediate_route_keeps_the_requested_page(page):
    flow,first=begin(page,tree(page['categories'][-1],[('intermediate',page['intermediate'][0],None)]))
    assert first['guide_command']['target_node_id']=='intermediate',first
    second=flow.send('observe',accessibility_tree=tree(page['intermediate'][0],[('destination',page['titles'][0],None)],at=2000))
    assert second['guide_command']['target_node_id']=='destination',second
    assert second['task_status']!='COMPLETED'
    final=flow.send('observe',accessibility_tree=tree(page['titles'][0],at=3000))
    assert final['metadata']['task_outcome']['technical']['status']=='OPENED'


def test_primary_provider_is_not_called_to_infer_arrival(monkeypatch):
    from app.services.screen_jev import JevScreenResolver
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER','jev')
    monkeypatch.setenv('TYPESAFE_API_KEY','synthetic-test-key')
    monkeypatch.setenv('JEV_MODEL','jev-1.14.0')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE','0.8')
    def forbidden(*args,**kwargs): raise AssertionError('Arrival must be verified from the native heading')
    monkeypatch.setattr(JevScreenResolver,'resolve',forbidden)
    page=next(p for p in PAGES if p['key']=='wifi')
    _,body=begin(page,tree('Wi-Fi'))
    assert body['task_status']=='COMPLETED'
    assert body['metadata']['task_outcome']['technical']['status']=='OPENED'


def test_result_request_cannot_report_unobserved_navigation_as_a_change():
    page=next(p for p in PAGES if p['key']=='wifi')
    flow,first=begin(page,tree('설정',[('destination','Wi-Fi',None)]))
    response=flow.client.post('/api/v1/agent/commands',json=flow.payload('request_result'))
    assert response.status_code==409
    assert flow.client.get('/api/v1/agent/sessions/'+flow.session).json()['status']=='IN_PROGRESS'
