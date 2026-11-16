"""A classifier must never reverse an explicit brightness or volume direction."""
import json
from pathlib import Path

import httpx
import pytest

from app.services.goal_inputs import GoalInputRequirements
from app.services.goal_jev import JevGoalClassifier
from app.services.goal_llm import LocalGoalLlm
from tests.test_goal_classifier import completion
from tests.test_goal_jev import response_for
from tests.test_task_outcomes import Flow


@pytest.mark.parametrize('provider', ['self_hosted', 'jev'])
@pytest.mark.parametrize('with_inputs', [False, True])
@pytest.mark.parametrize('setting,text,direction', [
    ('brightness', '화면 밝기 낮춰줘', 'decrease'),
    ('brightness', '화면 밝기를 내려 주세요', 'decrease'),
    ('brightness', '화면을 어둡게 해줘', 'decrease'),
    ('brightness', '화면 밝기를 높여줘', 'increase'),
    ('brightness', '화면을 밝게 해줘', 'increase'),
    ('volume', '미디어 음량 낮춰줘', 'decrease'),
    ('volume', '음악 소리를 내려줘', 'decrease'),
    ('volume', '미디어 음량 올려줘', 'increase'),
])
def test_model_cannot_reverse_explicit_setting_direction(monkeypatch, provider, with_inputs, setting, text, direction):
    monkeypatch.setenv('SELF_HOSTED_LLM_URL', 'https://inference.example/v1/chat/completions')
    monkeypatch.setenv('SELF_HOSTED_LLM_MODEL', 'click-goal')
    monkeypatch.setenv('SELF_HOSTED_LLM_TOKEN', 'test-only-secret')
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-secret')
    monkeypatch.setenv('JEV_MODEL', 'jev-1.14.0')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE', '0.8')
    catalog = json.loads((Path(__file__).parents[1] / 'app/task_graphs/goal_catalog.json').read_text(encoding='utf-8'))
    candidates = [g for g in catalog['goals'] if g['goal_type'].startswith(f'settings.{setting}.')]
    family = GoalInputRequirements().family(f'settings.{setting}.{direction}')
    for predicted in [direction, 'increase' if direction == 'decrease' else 'decrease']:
        goal = f'settings.{setting}.{predicted}'
        index = next(i for i, candidate in enumerate(candidates) if candidate['goal_type'] == goal)
        def send(payload, **kwargs):
            if provider == 'jev':
                return httpx.Response(200, json=response_for(payload, f'goal_{index}'))
            result = {'goal_type': goal}
            if with_inputs:
                result['inputs'] = {'direction': predicted}
            return completion(result)
        classifier = JevGoalClassifier(send) if provider == 'jev' else LocalGoalLlm(send)
        result = (classifier.interpret_inputs(text, candidates, family['fields']) if with_inputs
                  else classifier.select(text, candidates))
        if predicted != direction:
            assert result is None, (text, result)
        else:
            assert (result['goal_type'] if with_inputs else result) == goal


@pytest.mark.parametrize('text', ['화면 밝기 낮춰줘', '화면 밝기를 낮춰 줘', '밝기를 내려 주세요'])
def test_lower_request_keeps_direction_in_confirmation_graph_and_slider(text):
    from tests.test_expanded_settings import control
    flow = Flow()
    ready = flow.resolve(text)
    assert ready['goal']['goal_type'] == 'settings.brightness.decrease'
    assert ready['preparation']['values']['direction'] == 'decrease'
    assert ready['preparation']['graph_reference']['task_id'] == 'SET-brightness-decrease'
    assert '낮추기' in ready['message'] and '높이기' not in ready['message']
    first = flow.send('start_task', preparation_version='goal-inputs.v1', source_text=text,
        goal_resolution_id=ready['preparation']['id'], goal=ready['goal'],
        device_context={'platform': 'android', 'locale': 'ko-KR'}, accessibility_tree=control('brightness'))
    accepted = flow.send('respond_confirmation', user_confirmation='accepted',
        confirmation_id=first['metadata']['confirmation']['id'], accessibility_tree=control('brightness', at=1100))
    assert accepted['guide_command']['render_policy']['direction'] == 'left'
    assert '왼쪽' in accepted['guide_command']['message']
