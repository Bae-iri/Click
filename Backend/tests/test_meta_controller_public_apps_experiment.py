"""Frozen per-app capabilities never grant cross-app or value-changing actions."""
from contextlib import contextmanager
from http.client import HTTPConnection
import json
from threading import Thread
from unittest.mock import Mock

import pytest

from app.schemas.app_task import AppTaskRequest, AppTaskResponse, TaskAction, TaskExpectation
from scripts.serve_meta_controller_public_apps_experiment import (
    AUTHORIZATION, TASK_PATH, VERSION, allowed, create_server, load_contract,
    navigation_action_allowed, navigation_capabilities, reference)


PACKAGES=('org.example.reader','org.example.travel','org.example.utility')


def manifest_data():
    return dict(schema_version='click.native-public-apps.v1',locale='en-US',apps=[dict(
        package=p,label=f'App {i}',home_action='launcher',public_labels=['Library','목록','검색어 입력','Enabled'],
        public_resources=['','android:id/title',p+':id/switch_widget',p+':id/search'],
        navigation_controls=[dict(label='Library',resource='android:id/title')]) for i,p in enumerate(PACKAGES)],
        tasks=[dict(task_id=f'app_{i}_{j}',package=p,goal=f'Open library {i} {j}',start_path=[],gui_path=['Library'],
            oracle_checks=[dict(kind='passive_title',label='Library',resources=[])]) for i,p in enumerate(PACKAGES) for j in range(2)])


def contract(tmp_path,data=None):
    path=tmp_path/'catalogue.json';path.write_text(json.dumps(data or manifest_data()))
    return path,load_contract(path)


def request(index=0):
    package=PACKAGES[index]
    return AppTaskRequest.model_validate(dict(request_id='request-test',goal=f'Open library {index} 0',
        state=dict(task_id='task-test'),apps=[dict(package=package,label='App')],
        screen=dict(status='public',package=package,observation_id='native-frame',fingerprint='a'*64,
            captured_at_ms=500,width=1080,height=2400,nodes=[dict(id='native-tab',label='Library',
                resource='android:id/title',role='tab',clickable=True,selected=False,bounds=[10,20,500,100]) ])))


def response(req):
    return AppTaskResponse(request_id=req.request_id,task_id=req.state.task_id,observation_id=req.screen.observation_id,
        fingerprint=req.screen.fingerprint,status='needs_input',message='test',diagnostic=dict(called=True,
            model='gemini-3.8-flash',latency_ms=12,recovery_context_used=False,capabilities_applied=True,
            allowed_actions_context_used=True,allowed_action_count=3,recovery_blocked_action_count=0))


@pytest.mark.parametrize('index',range(3))
def test_each_frozen_package_gets_only_its_own_native_navigation(tmp_path,index):
    _,item=contract(tmp_path);req=request(index)
    assert allowed(req,item)
    assert navigation_capabilities(req,item).pairs==frozenset({('tap','native-tab'),('back',''),('open_app',PACKAGES[index])})
    result=response(req);result.action=TaskAction(id='a',tool='tap',package=PACKAGES[index],target_id='native-tab',
        bounds=req.screen.nodes[0].bounds,expectation=TaskExpectation(kind='selection_changed',selected=True))
    assert navigation_action_allowed(req,result,item)
    result.action.package=PACKAGES[(index+1)%3]
    assert not navigation_action_allowed(req,result,item)
    body=req.model_dump(mode='json');body['goal']='Open library 2 1' if index!=2 else 'Open library 0 1'
    assert not allowed(AppTaskRequest.model_validate(body),item)


@pytest.mark.parametrize('change',['few_apps','few_tasks','duplicate_goal','wrong_package','unsafe_selector','bad_resource'])
def test_contract_cannot_hide_unfrozen_or_value_writing_scope(tmp_path,change):
    data=manifest_data()
    if change=='few_apps':data['apps'].pop()
    elif change=='few_tasks':data['tasks'].pop()
    elif change=='duplicate_goal':data['tasks'][1]['goal']=data['tasks'][0]['goal']
    elif change=='wrong_package':data['tasks'][0]['package']='org.unselected.app'
    elif change=='unsafe_selector':data['apps'][0]['navigation_controls'][0]['label']='Delete account'
    else:data['apps'][0]['public_resources'].append('https://private/value')
    with pytest.raises(ValueError):contract(tmp_path,data)


def test_readonly_toggle_and_input_context_never_become_tools(tmp_path):
    _,item=contract(tmp_path);body=request().model_dump(mode='json');p=PACKAGES[0]
    body['screen']['nodes'] += [dict(id='toggle',label='Enabled',resource=p+':id/switch_widget',role='toggle',
        clickable=True,checked=True,bounds=[10,200,500,300]),dict(id='search',label='검색어 입력',resource=p+':id/search',
        role='text_field',editable=True,bounds=[10,400,500,500])]
    req=AppTaskRequest.model_validate(body);assert allowed(req,item)
    pairs=navigation_capabilities(req,item).pairs
    assert ('tap','toggle') not in pairs and ('set_text','search') not in pairs
    body['screen']['nodes'][-1]['has_value']=True
    assert not allowed(AppTaskRequest.model_validate(body),item)


def test_scroll_requires_an_explicit_frozen_navigation_list(tmp_path):
    data=manifest_data();data['apps'][0]['public_resources'].append(PACKAGES[0]+':id/navigation_list')
    _,item=contract(tmp_path,data);body=request().model_dump(mode='json')
    body['screen']['nodes'].append(dict(id='list',label='목록',resource=PACKAGES[0]+':id/navigation_list',
        role='list',scrollable=True,bounds=[10,200,1000,2000]))
    req=AppTaskRequest.model_validate(body);assert allowed(req,item)
    assert ('scroll_forward','list') not in navigation_capabilities(req,item).pairs
    data['apps'][0]['navigation_controls'].append(dict(label='목록',resource=PACKAGES[0]+':id/navigation_list'))
    _,item=contract(tmp_path,data)
    assert ('scroll_forward','list') in navigation_capabilities(req,item).pairs


@contextmanager
def server(tmp_path,service):
    path,_=contract(tmp_path);output=tmp_path/'terminal.jsonl'
    httpd=create_server(manifest=path,output_path=output,port=0,allow_live=True,service=service)
    thread=Thread(target=httpd.serve_forever,kwargs={'poll_interval':.01},daemon=True);thread.start()
    try:yield httpd.server_port,output
    finally:httpd.shutdown();httpd.server_close();thread.join(2)


def test_actual_handler_preserves_native_request_clock_and_applies_trusted_mask(tmp_path):
    req=request();service=Mock();service.step.return_value=response(req)
    with server(tmp_path,service) as (port,output):
        conn=HTTPConnection('127.0.0.1',port,timeout=3)
        conn.request('POST',TASK_PATH,json.dumps(req.model_dump(mode='json')).encode(),{
            'Authorization':AUTHORIZATION,'Content-Type':'application/json','X-Click-Request-Ref':reference(req.request_id),
            'X-Click-Task-Ref':reference(req.state.task_id),'X-Click-Budget-Ms':'15000'})
        reply=conn.getresponse();payload=json.loads(reply.read());conn.close();assert reply.status==200
    sent=service.step.call_args.args[0]
    assert sent.model_dump(mode='json')==req.model_dump(mode='json')
    assert service.step.call_args.kwargs['action_capabilities'].pairs==navigation_capabilities(req,load_contract(tmp_path/'catalogue.json')).pairs
    record=json.loads(output.read_text());assert record['schema_version']==VERSION
    assert record['model_call_state']=='observed' and record['model_calls']==1
    assert record['capabilities_applied'] is True and payload['task_id']==req.state.task_id


def test_unknown_package_is_rejected_before_service_with_confirmed_zero_attempt(tmp_path):
    service=Mock();req=request();body=req.model_dump(mode='json');body['screen']['package']='org.unselected.app'
    with server(tmp_path,service) as (port,output):
        conn=HTTPConnection('127.0.0.1',port,timeout=3);conn.request('POST',TASK_PATH,json.dumps(body),{
            'Authorization':AUTHORIZATION,'Content-Type':'application/json'})
        reply=conn.getresponse();reply.read();conn.close();assert reply.status==403
    service.step.assert_not_called();record=json.loads(output.read_text())
    assert record['model_call_state']=='not_started' and record['model_calls']==0
