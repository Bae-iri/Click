from copy import deepcopy
import pytest
from fastapi.testclient import TestClient
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.types import AccessibilityNode
from app.services.screen_jev import JevScreenResolver
from app.task_graphs.validation import load_graph
from tests.test_screen_jev import scene, sender_for


def test_an_unnamed_slider_on_generic_volume_page_is_not_a_media_candidate(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only-key')
    graph,edge,n,goal=scene('volume')
    from dataclasses import replace
    from ai_agent.runtime.types import NormalizedNode
    nodes=(replace(n,label='',label_parts=()),NormalizedNode('title','음량','text',package_name=n.package_name))
    resolver=JevScreenResolver(lambda *a,**kw:pytest.fail('Unidentified channel must not be sent to Jev'))
    assert resolver.resolve(nodes=nodes,graph=graph,goal=goal) is None
    assert resolver.last_diagnostics['status']=='no_candidates'


def test_volume_rows_ground_only_their_own_channel(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only-key')
    graph,edge,_,goal=scene('volume')
    raw=[AccessibilityNode('root',package_name='com.android.settings',bounds_in_screen=(0,0,1080,2400))]
    for i,label in enumerate(('미디어','벨소리','알림','시스템')):
        top=100+i*400
        raw += [AccessibilityNode(f'row{i}',parent_id='root',package_name='com.android.settings',bounds_in_screen=(0,top,1080,top+300)),
            AccessibilityNode(f'label{i}',parent_id=f'row{i}',package_name='com.android.settings',text=label,
                class_name='android.widget.TextView',bounds_in_screen=(20,top,600,top+80)),
            AccessibilityNode(f'slider{i}',parent_id=f'row{i}',package_name='com.android.settings',content_description='4',
                class_name='android.widget.SeekBar',bounds_in_screen=(40,top+120,900,top+180),extras={'progress':3,'min':0,'max':15})]
    nodes=normalize_accessibility_tree(tuple(raw),screen_bounds=(0,0,1080,2400))
    def send(payload,**kw):
        controls=[v for v in payload['questions']['screen']['criteria'].values() if isinstance(v,dict)]
        assert len(controls)==1
        return sender_for(edge)(payload,**kw)
    result=JevScreenResolver(send).resolve(nodes=nodes,raw_nodes=tuple(raw),graph=graph,goal=goal)
    assert result.target_node_id=='slider0'


def test_executable_media_graph_cannot_remove_channel_identity_requirement():
    graph,*_=scene('volume');body=deepcopy(graph.to_dict())
    # JSON form expected by graph import/approval, not runtime dataclass tuples.
    import json
    body=json.loads(json.dumps(body))
    edge=next(t for t in body['transitions'] if t['target_role']=='slider')
    edge['selector']['allow_unlabelled_in_context']=True
    with pytest.raises(ValueError,match='identified channel'):load_graph(body)


def test_public_build_identity_contains_no_configuration_or_credentials(monkeypatch):
    from app.main import create_app
    monkeypatch.setenv('RENDER_GIT_COMMIT','a'*40)
    body=TestClient(create_app()).get('/version').json()
    assert body['source_revision']=='a'*40
    assert body['target_validation']=='settings-slider.v1'
    assert len(body['source_sha256'])==64
    assert set(body)=={'schema_version','source_revision','source_sha256','target_validation','runtime_configuration'}
    assert set(body['runtime_configuration']) <= {'provider','code_hash','model','minimum_confidence'}


def test_device_runner_stops_before_adb_if_server_is_not_the_tested_release():
    from scripts.native_settings_check import verify_build
    import httpx
    wrong=httpx.Response(200,request=httpx.Request('GET','https://example.invalid/version'),
        json={'target_validation':'settings-slider.v1','source_sha256':'0'*64})
    with pytest.raises(ValueError,match='not the tested release'):
        verify_build('https://example.invalid',get=lambda *a,**kw:wrong)
