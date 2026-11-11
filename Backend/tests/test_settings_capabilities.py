"""Cross-component capability contract, executable assets, and build identity."""
import json
from hashlib import sha256
from pathlib import Path
import pytest
from scripts.export_settings_capabilities import build, OUTPUT
from app.core import validation
from app.main import create_app
from fastapi.testclient import TestClient
from ai_agent.runtime.capabilities import identity, navigation_state
from app.task_graphs import releases


def test_generated_contract_exactly_matches_every_approved_graph():
    contract=json.loads(OUTPUT.read_text())
    assert contract==build()
    assert len(contract['goals'])==110
    assert {'S_CATEGORY','S_INTERMEDIATE','S2_CATEGORY','S2_DISPLAY_MENU'}<=set(contract['navigation_states'])
    assert {'연결','블루투스','wi-fi','알림','배터리'}<=set(contract['navigation_labels'])
    assert not {'확인','허용','삭제','초기화','비밀번호'}&set(contract['navigation_labels'])
    for release,graph in releases():
        if not release['approved']:continue
        for state in contract['goals'][graph.definition.goal_type]['navigation']:
            if state!='S0_UNKNOWN':assert navigation_state(graph,state)
        for edges in contract['goals'][graph.definition.goal_type]['navigation'].values():
            assert all(edge['role'] in {'menu_item','app_icon'} for edge in edges)

@pytest.mark.parametrize('asset',['settings_execution.json','settings_capabilities.json','mvp_goals.json','vision_vocabulary.json','screen_privacy_policy.json'])
def test_execution_asset_changes_always_change_source_digest(tmp_path,monkeypatch,asset):
    monkeypatch.setattr(validation,'ROOT',tmp_path);monkeypatch.setattr(validation,'BACKEND',tmp_path/'Backend')
    folder=tmp_path/'AI-Agent/goal_catalog';folder.mkdir(parents=True)
    p=folder/asset;p.write_text('{"version":1}')
    before=validation.source_digest();p.write_text('{"version":2}')
    assert before!=validation.source_digest()


def test_public_version_reports_the_shared_contract_identity():
    with TestClient(create_app('sqlite+pysqlite:///:memory:')) as client:
        result=client.get('/version').json()['capability_contract']
    assert result==identity()=={'schema_version':'settings-capabilities.v1','sha256':sha256(OUTPUT.read_bytes()).hexdigest()}


def test_runtime_identity_includes_executable_data(tmp_path,monkeypatch):
    from app.clients.runtime_agent import runtime_configuration
    import ai_agent.runtime as runtime
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER','rules')
    runtime_dir=tmp_path/'src/ai_agent/runtime';runtime_dir.mkdir(parents=True)
    fake_init=runtime_dir/'__init__.py';fake_init.write_text('# fixed runtime')
    monkeypatch.setattr(runtime,'__file__',str(fake_init))
    data=tmp_path/'goal_catalog';data.mkdir();p=data/'settings_execution.json';p.write_text('{"v":1}')
    before=runtime_configuration();p.write_text('{"v":2}')
    assert before['code_hash']!=runtime_configuration()['code_hash']
