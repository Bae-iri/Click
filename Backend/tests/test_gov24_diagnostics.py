"""Current login and six service flows expose guard causes without exporting content."""
import json
from unittest.mock import Mock

import pytest
from pydantic import ValidationError
from sqlalchemy import select

from app.models.runtime_event import RuntimeEvent
from app.schemas.public_guide import PRIVACY_REASONS, PublicGuideRequest, PublicGuideScreen
from app.services.public_guide import PublicGuideService
from tests.test_gov24_navigation import CivilFlow, view
from tests.test_public_guide import login, screen as login_screen  # noqa: F401


def decide(screen=None, *, civil=True, steps=0, command='start', selected=False):
    import time
    service = PublicGuideService(None, None, selector=Mock())
    state = dict(goal={'goal_type': 'gov24.resident_copy.open' if civil else 'gov24.login'}, steps=steps)
    if selected:
        state['selected_option'] = 'method_simple'
    request = PublicGuideRequest(request_id='r', session_id='s', expected_version=steps,
                                 command=command, screen=screen, goal_resolution_id='p' if command == 'start' else None)
    result, evidence, state = service.decide(request, state, int(time.time()))
    return result, evidence, state, service


@pytest.mark.parametrize('civil', [False, True])
@pytest.mark.parametrize('detail,pair', PRIVACY_REASONS.items())
def test_native_guard_causes_stay_distinct_in_response_and_encrypted_event_material(civil, detail, pair):
    status, reason = pair
    source = dict(view(), status=status, privacy_reason=detail)
    if reason:
        source['handoff_reason'] = reason
    result, evidence, state, service = decide(source, civil=civil)
    diagnostic = result.diagnostic
    assert diagnostic['code'] == evidence['reason'] == detail
    assert diagnostic['status'] == ('BLOCKED' if status in {'handoff', 'sensitive'} else 'UNKNOWN')
    assert diagnostic['facts']['native_detail_available'] is True
    assert result.target_node_id is None
    assert diagnostic == evidence['diagnostic'] == state['public_guide_diagnostic']
    service.selector.choose.assert_not_called()
    encoded = json.dumps(diagnostic, ensure_ascii=False)
    for forbidden in (source['observation_id'], 'node_id', 'bounds', 'resource_id'):
        assert forbidden not in encoded


@pytest.mark.parametrize('civil', [False, True])
@pytest.mark.parametrize('change,expected', [
    ({'captured_at_ms': 0}, 'stale_observation'),
    ({'captured_at_ms': 9999999999999}, 'future_observation'),
    ({'package_name': 'other.private.app'}, 'package_mismatch'),
    ({'status': 'unrecognized', 'nodes': []}, 'unrecognized_screen'),
    ({'status': 'outside_app', 'nodes': []}, 'outside_gov24_app'),
    ({'nodes': []}, 'no_exported_nodes'),
])
def test_missing_observation_is_unknown_and_never_mistaken_for_authentication(civil, change, expected):
    source = dict(view('nav_resident') if civil else login_screen('method_simple'), **change)
    result, _, _, service = decide(source, civil=civil)
    assert result.diagnostic['code'] == expected and result.diagnostic['status'] == 'UNKNOWN'
    assert result.status == 'IN_PROGRESS' and result.target_node_id is None
    assert 'other.private.app' not in json.dumps(result.diagnostic)
    service.selector.choose.assert_not_called()


@pytest.mark.parametrize('civil', [False, True])
def test_missing_observation_and_turn_budget_are_different(civil):
    missing, _, _, _ = decide(civil=civil)
    assert missing.diagnostic['code'] == 'observation_missing'
    exhausted, evidence, _, _ = decide(civil=civil, steps=24)
    assert exhausted.status == 'BLOCKED' and exhausted.diagnostic['category'] == 'budget'
    assert exhausted.diagnostic['facts'] == {'step': 25, 'max_turns': 24}
    assert evidence['reason'] == 'turn_limit'


@pytest.mark.parametrize('reason', ['credential_input', 'certificate_selection', 'external_auth', 'consent_required'])
def test_legacy_apps_keep_known_cause_without_guessing_a_more_specific_field(reason):
    result, _, _, _ = decide(dict(view(), status='handoff', handoff_reason=reason))
    assert result.diagnostic['code'] == reason
    assert result.diagnostic['facts']['native_detail_available'] is False


def test_unknown_followup_of_login_method_is_not_claimed_to_be_an_authentication_screen():
    result, _, _, _ = decide(dict(view(), status='unrecognized'), civil=False, selected=True)
    assert result.outcome == 'handoff'
    assert result.diagnostic['code'] == 'authentication_step'
    assert result.diagnostic['status'] == 'UNKNOWN'
    assert '인증 화면인지는 확인하지 못했' in result.message


def test_normal_public_login_and_service_selection_keep_the_existing_behavior():
    for civil, source in ((False, login_screen('method_simple')), (True, view('nav_resident'))):
        result, evidence, _, _ = decide(source, civil=civil)
        assert result.diagnostic is None and evidence['observation_check']['code'] == 'public_observation'
        assert result.target_node_id if civil else result.question


@pytest.mark.parametrize('source', [
    dict(view('nav_resident'), privacy_reason='password_field'),
    dict(view(), status='handoff', handoff_reason='external_auth', privacy_reason='consent_prompt'),
    dict(view(), status='unrecognized', privacy_reason='홍길동 010-1234-5678'),
])
def test_raw_or_contradictory_diagnostics_are_rejected(source):
    with pytest.raises(ValidationError):
        PublicGuideScreen.model_validate(source)


def test_private_cause_survives_replay_snapshot_and_event_storage(login):
    f = CivilFlow(login.client, login.app, '정부24 주민등록등본 화면 찾아줘')
    prepared = f.resolve()['preparation']
    first = f.command('start', goal_resolution_id=prepared['id'])
    assert first['diagnostic']['code'] == 'observation_missing'
    source = dict(view(), status='handoff', handoff_reason='credential_input', privacy_reason='sensitive_input_field')
    body = dict(request_id='diagnostic-replay', session_id=f.session, expected_version=f.version,
                command='observe', screen=source)
    path = '/api/v1/agent/public-guide/commands'
    response = login.client.post(path, json=body)
    assert response.status_code == 200, response.text
    result = response.json()
    assert result['diagnostic']['code'] == 'sensitive_input_field'
    assert login.client.post(path, json=body).json() == result
    f.version = result['session_version']
    restored = f.command('snapshot')
    assert restored['diagnostic'] == result['diagnostic'] and restored['message'] == result['message']
    assert restored['subgoal'] == 'handoff' and restored['target_node_id'] is None
    with login.app.state.session_factory() as db:
        event = db.scalars(select(RuntimeEvent).where(RuntimeEvent.session_id == f.session)
                           .order_by(RuntimeEvent.sequence.desc())).first()
        assert event.payload['public_guide']['diagnostic'] == result['diagnostic']
