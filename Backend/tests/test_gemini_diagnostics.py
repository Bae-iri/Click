import httpx
import pytest

from scripts.diagnose_gemini import generation_result
from app.services.gov24_planner import GeminiGovernmentPlanner


def response(text=None, finish='STOP', **extra):
    parts = [] if text is None else [{'text': text}]
    return {'candidates': [{'finishReason': finish, 'content': {'parts': parts}}], **extra}


@pytest.mark.parametrize('body', [
    {}, [], {'candidates': None}, {'candidates': [{}]},
    response(), response('', 'STOP'), response('OK', 'MAX_TOKENS'),
    response('OK', 'SAFETY'),
    {'candidates': [{'finishReason': 'STOP', 'content': {'parts': [{'text': 'hidden', 'thought': True}]}}]},
])
def test_http_200_alone_does_not_prove_generation(body):
    assert generation_result(body)['success'] is False


@pytest.mark.parametrize('text', [
    'not json', '{"subgoals":["unknown"]}',
    '{"subgoals":["adjust_setting"],"extra":true}',
    '{"subgoals":["unknown"],"subgoals":["adjust_setting"]}',
])
def test_structured_probe_requires_the_requested_plan(text):
    result = generation_result(response(text), structured=True)
    assert result['schema_valid'] is False
    assert result['success'] is False


def test_valid_plan_reports_served_model_without_echoing_generated_content():
    result = generation_result(response('{"subgoals":["adjust_setting"]}',
        modelVersion='gemini-3.8-flash'), structured=True)
    assert result['success'] and result['schema_valid']
    assert result['served_model'] == 'gemini-3.8-flash'
    assert 'subgoals' not in str(result)


def test_minimal_probe_requires_real_non_thought_text():
    result = generation_result(response('OK'))
    assert result['success']
    assert result['output_characters'] == 2


def test_government_planner_keeps_sanitized_overload_cause():
    def sender(payload, timeout):
        return httpx.Response(503, json={'error': {'code': 503, 'status': 'UNAVAILABLE',
            'message': 'This model is currently experiencing high demand. PRIVATE_ECHO'}},
            headers={'Retry-After': '2'})
    plan, diagnostic = GeminiGovernmentPlanner(sender).plan(
        'gov24.resident_copy.open', 'public_menu', ['nav_resident'], [], [])
    assert plan is None
    assert diagnostic['provider_cause'] == 'model_high_demand'
    assert diagnostic['api_status'] == 'UNAVAILABLE'
    assert diagnostic['retry_after_seconds'] == 2
    assert diagnostic['retryable'] is True
    assert 'PRIVATE_ECHO' not in str(diagnostic)


def test_government_planner_distinguishes_transport_timeout_from_invalid_plan():
    def sender(payload, timeout):
        raise httpx.ReadTimeout('PRIVATE_TRANSPORT_DETAILS')
    plan, diagnostic = GeminiGovernmentPlanner(sender).plan(
        'gov24.resident_copy.open', 'public_menu', ['nav_resident'], [], [])
    assert plan is None and diagnostic['called']
    assert diagnostic['error_type'] == 'ReadTimeout'
    assert diagnostic['failure_kind'] == 'provider'
    assert diagnostic['retryable'] is True
    assert 'PRIVATE_TRANSPORT_DETAILS' not in str(diagnostic)
