"""Real user brightness phrases must prepare and start the brightness graph."""
from unittest.mock import Mock
import pytest
from tests.test_task_outcomes import Flow
from tests.test_expanded_settings import control

CASES = [
    ('지금 화면 어둡게 하고싶어', 'decrease'),
    ('화면 어둡게 하고 싶어', 'decrease'),
    ('화면을 어둡게 해줘', 'decrease'),
    ('화면 좀 어둡게 해줘', 'decrease'),
    ('화면을 좀 어둡게 해줘', 'decrease'),
    ('화면이 너무 밝아', 'decrease'),
    ('화면 밝게 하고 싶어', 'increase'),
    ('화면을 밝게 해줘', 'increase'),
    ('화면이 어두워', 'increase'),
    ('화면이 너무 어두워', 'increase'),
    ('화면 좀 밝게 해줘', 'increase'),
    ('화면을 조금 더 밝게 해줘', 'increase'),
]

@pytest.mark.parametrize('text,direction', CASES)
def test_user_phrase_prepares_and_starts_the_pinned_brightness_graph(text, direction):
    f = Flow()
    classifier = Mock()
    classifier.interpret_inputs.side_effect = AssertionError('Explicit brightness direction must not need a model')
    f.app.state.turn_service.goal_preparation.llm = classifier
    ready = f.resolve(text)
    assert ready['resolution_status'] == 'confirmation_required', ready
    assert ready['goal']['goal_type'] == 'settings.brightness.' + direction
    assert ready['preparation']['values'] == {'scope': 'system', 'direction': direction}
    ref = ready['preparation']['graph_reference']
    assert ref['task_id'] == 'SET-brightness-' + direction
    started = f.send('start_task', preparation_version='goal-inputs.v1', source_text=text,
        device_context={'platform': 'android', 'locale': 'ko-KR'}, goal=ready['goal'],
        goal_resolution_id=ready['preparation']['id'], accessibility_tree=control('brightness'))
    assert started['graph_reference'] == {k: ref[k] for k in ('task_id', 'version', 'sha256')}
    assert started['task_status'] == 'WAITING_CONFIRMATION'
    assert started['guide_command']['target_node_id'] == 'target'

@pytest.mark.parametrize('text', ['화면 어둡게 하지 마', '화면을 밝게 하지 마'])
def test_negation_never_selects_a_brightness_graph(text):
    result = Flow().resolve(text)
    assert result['goal'] is None
    assert result['preparation']['graph_reference'] is None

@pytest.mark.parametrize('text', ['화면 다크모드 켜줘', '화면을 어두운 테마로 바꿔줘'])
def test_explicit_dark_theme_is_not_changed_to_a_brightness_request(text):
    result = Flow().resolve(text)
    assert result['goal']['goal_type'] == 'settings.dark_mode.enable'


def test_new_phrase_keeps_an_explicit_app_scope_unsupported():
    result = Flow().resolve('유튜브 앱에서만 화면 어둡게 해줘')
    assert result['preparation']['status'] == 'unsupported'
    assert result['goal'] is None
