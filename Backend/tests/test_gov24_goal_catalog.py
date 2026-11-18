"""The public catalog advertises Government24 screens without inventing execution."""
import json
from pathlib import Path

import pytest
from app.schemas.agent_turn import TypedGoalRequest
from tests.test_public_guide import login  # noqa: F401
from tests.test_gov24_navigation import CivilFlow

ROOT=Path(__file__).parents[2]
CATALOG=json.loads((ROOT/'Backend/app/task_graphs/goal_catalog.json').read_text(encoding='utf-8'))
GOVERNMENT=[g for g in CATALOG['goals'] if g['target_app']=='gov24']


def test_catalog_copies_and_legacy_scope_are_separate():
    assert (ROOT/'AI-Agent/goal_catalog/mvp_goals.json').read_bytes()==(
        ROOT/'Backend/app/task_graphs/goal_catalog.json').read_bytes()
    assert CATALOG['version']=='2.5.0'
    assert len(GOVERNMENT)==20
    assert not any(g['target_app']=='coupang' for g in CATALOG['goals'])
    archived=json.loads((ROOT/'AI-Agent/goal_catalog/legacy_coupang_goals.json').read_text(encoding='utf-8'))
    assert archived['version']=='2.4.1' and len(archived['goals'])==71
    assert all(g['target_app']=='coupang' for g in archived['goals'])


@pytest.mark.parametrize('entry',[g for g in GOVERNMENT if g['execution_status']=='catalog_only'],ids=lambda g:g['goal_type'])
def test_planned_government_requests_never_start_a_graph_or_guess_login(login,entry):
    service=login.app.state.turn_service.goal_preparation
    service.llm.interpret_inputs=lambda *a,**kw:pytest.fail('Known planned goal must not ask a model to invent a graph')
    f=CivilFlow(login.client,login.app,entry['utterances'][0])
    body=f.resolve()
    assert body['resolution_status']=='unsupported',body
    assert body['goal'] is None and body['preparation']['graph_reference'] is None
    assert body['preparation']['public_guide_goal'] is None
    assert '아직 준비 중' in body['message']
    assert entry['display_name'] in body['message']


@pytest.mark.parametrize('entry',[g for g in GOVERNMENT if g['execution_status']=='public_guide'],ids=lambda g:g['goal_type'])
def test_connected_government_goals_keep_the_existing_public_guide_contract(login,entry):
    f=CivilFlow(login.client,login.app,entry['utterances'][0])
    body=f.resolve()
    assert body['resolution_status']=='confirmation_required',body
    assert body['preparation']['execution_mode']=='public_guide'
    assert body['preparation']['public_guide_goal']['goal_type']==entry['goal_type']
    assert body['goal'] is None and body['preparation']['graph_reference'] is None


def test_document_history_is_not_a_new_document_navigation_request(login):
    f=CivilFlow(login.client,login.app,'정부24 주민등록등본 신청 내역 보여줘')
    body=f.resolve()
    assert body['resolution_status']=='unsupported'
    assert body['preparation']['public_guide_goal'] is None
    assert '신청 내역' in body['message']


def test_settings_use_the_new_catalog_while_coupang_is_legacy_only():
    TypedGoalRequest(catalog_version='2.5.0',goal_type='settings.volume.increase',
        target_app='android_settings',slots={'setting':'volume','direction':'increase'})
    TypedGoalRequest(catalog_version='2.4.1',goal_type='coupang.cart.view',
        target_app='coupang',slots={'action':'view'})
    with pytest.raises(ValueError):
        TypedGoalRequest(catalog_version='2.5.0',goal_type='coupang.cart.view',
            target_app='coupang',slots={'action':'view'})


def test_old_client_gets_update_guidance_for_a_known_public_guide(login):
    from tests.test_goal_preparation import Flow
    login.app.state.turn_service.goal_preparation.llm.interpret_inputs=lambda *a,**kw:pytest.fail('Known client capability mismatch')
    body=Flow(login.client,login.app,'정부24에서 주민등록등본 발급해줘').resolve()
    assert body['resolution_status']=='unsupported'
    assert body['metadata']['goal_interpretation']['reason']=='unsupported'
    assert '최신 앱' in body['message']
