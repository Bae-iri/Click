"""Regressions for action causality, grounding, persistent exclusions and completion."""
from dataclasses import replace

import pytest
from ai_agent.runtime import ClickAgentEngine, UserActionEvent, UserActionType
from ai_agent.runtime.types import AgentTurnRequest, CommandType, VerificationStatus as Status, TargetSelector
from ai_agent.runtime.tasks import MVP_01_TASK_GRAPH as GRAPH
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.matcher import match_next_ui
from ai_agent.runtime.similarity import KeywordFallbackSimilarityScorer
from test_engine_flow import _settings_home_tree, _font_size_control_tree, _raw_goal, _node


class Turns:
    def __init__(self, graph=GRAPH):
        self.engine = ClickAgentEngine(task_graphs={graph.definition.task_id: graph},
                                       similarity_scorer=KeywordFallbackSimilarityScorer())
        self.index = 0

    def send(self, tree=None, *, at=None, event=None, observation=None, confirmation=None, applied_scale=None):
        self.index += 1
        return self.engine.handle_turn(AgentTurnRequest(
            request_id=f'r{self.index}', session_id='s',
            raw_goal=_raw_goal('글자 크기 키워줘') if self.index == 1 else None,
            accessibility_tree=tree if tree is not None else _settings_home_tree(),
            captured_at_ms=at, observation_id=observation or f'o{self.index}',
            user_action_event=event, user_confirmation=confirmation,
            applied_settings={"source": "android.provider.Settings.System.FONT_SCALE", "font_scale": applied_scale}
                if applied_scale is not None else {}))

    def event(self, response, *, at=None, event_id=None, action=UserActionType.TAP_COMPLETED):
        return UserActionEvent(action, response.guide_command.guide_command_id,
                               response.guide_command.target_node_id, occurred_at_ms=at,
                               event_id=event_id)

    @property
    def session(self):
        return self.engine.session_store.get('s')


def fail_attempt(turns, guide, at):
    waiting = turns.send(at=at + 100, event=turns.event(guide, at=at, event_id=f'event{at}'))
    assert waiting.verification_result.status == Status.WAITING_FOR_OBSERVATION
    failed = turns.send(at=at + 700)
    assert failed.verification_result.status == Status.NO_CHANGE
    assert failed.verification_result.count_attempt
    return failed


@pytest.mark.parametrize('move', [False, True])
def test_idle_observations_never_count_as_failed_attempts(move):
    turns = Turns()
    first = turns.send(at=1000)
    for index in range(10):
        nodes = tuple(replace(n, bounds_in_screen=(24, 320 + index, 1056, 408 + index))
                      if move and n.node_id == 'display' else n for n in _settings_home_tree())
        result = turns.send(nodes, at=2000 + index * 1000)
        assert result.verification_result.status == Status.WAITING_FOR_ACTION
        assert result.guide_command.target_node_id == first.guide_command.target_node_id
    assert not turns.session.transition_attempts
    assert not turns.session.failed_target_keys


def test_only_distinct_failed_actions_count_and_old_event_cannot_fail_retry():
    turns = Turns()
    first = turns.send(at=1000)
    event = turns.event(first, at=1100, event_id='same-event')
    turns.send(at=1200, event=event)
    failed = turns.send(at=1800, event=event)
    assert sum(turns.session.transition_attempts.values()) == 1
    assert failed.guide_command.guide_command_id != first.guide_command.guide_command_id
    replay = turns.send(at=2400, event=event)
    assert replay.verification_result.status == Status.WAITING_FOR_ACTION
    assert sum(turns.session.transition_attempts.values()) == 1


def test_failed_target_stays_excluded_after_no_target_and_node_id_change():
    # Remove the optional accessibility route so the first exclusion yields no target.
    graph = replace(GRAPH, transitions=tuple(t for t in GRAPH.transitions
                                           if t.transition_id != 'S1_TO_S3_ACCESSIBILITY_FALLBACK'))
    turns = Turns(graph)
    first = turns.send(at=1000)
    retry = fail_attempt(turns, first, 1100)
    excluded = fail_attempt(turns, retry, 1900)
    assert excluded.guide_command.target_node_id is None
    keys = turns.session.failed_target_keys
    moved = tuple(replace(n, node_id='display-new-index', bounds_in_screen=(24, 321, 1056, 409))
                  if n.node_id == 'display' else n for n in _settings_home_tree())
    following = turns.send(moved, at=3000)
    assert following.guide_command.target_node_id is None
    assert turns.session.failed_target_keys == keys
    turns.send(moved, at=4000)
    exhausted = turns.send(moved, at=4600)
    assert exhausted.debug['replan_decision']['strategy'] == 'backend_reselection'
    assert exhausted.guide_command.command_type == CommandType.HIGHLIGHT
    assert exhausted.guide_command.render_policy['recovery']['decision'] == 'handoff'
    following = turns.send(moved, at=6000)
    assert following.guide_command.render_policy == exhausted.guide_command.render_policy
    assert turns.session.recovery_count == graph.policy.recovery_limit


def test_exclusion_uses_alternate_graph_edge_and_explicit_priority():
    turns = Turns()
    first = turns.send(at=1000)
    assert first.match_result.transition_id == 'S1_TO_S2_DISPLAY_MENU'
    retry = fail_attempt(turns, first, 1100)
    alternate = fail_attempt(turns, retry, 1900)
    assert alternate.match_result.transition_id == 'S1_TO_S3_ACCESSIBILITY_FALLBACK'
    assert alternate.debug['replan_decision']['strategy'] == 'alternate_transition'
    reordered = replace(GRAPH, transitions=tuple(reversed(GRAPH.transitions)))
    assert Turns(reordered).send().match_result.transition_id == first.match_result.transition_id


@pytest.mark.parametrize('kind', ['brightness', 'button', 'disabled', 'hidden', 'zero_area', 'other_package'])
def test_invalid_controls_cannot_be_selected_for_drag(kind):
    nodes = list(_font_size_control_tree(slider_value=2))
    index = next(i for i, n in enumerate(nodes) if n.node_id == 'font-slider')
    changes = {
        'brightness': {'text': '밝기'},
        'button': {'text': '크게', 'class_name': 'android.widget.Button', 'clickable': True},
        'disabled': {'enabled': False}, 'hidden': {'visible_to_user': False},
        'zero_area': {'bounds_in_screen': (0, 0, 0, 0)},
        'other_package': {'package_name': 'com.other.app'},
    }[kind]
    nodes[index] = replace(nodes[index], **changes)
    matched = match_next_ui('S4_FONT_SIZE_CONTROL', normalize_accessibility_tree(tuple(nodes)), GRAPH)
    assert matched.target_node_id is None


def test_identical_candidates_abstain_independent_of_input_order():
    nodes = normalize_accessibility_tree((_node('a', '디스플레이'), _node('b', '디스플레이')))
    for ordering in (nodes, tuple(reversed(nodes))):
        result = match_next_ui('S1_SETTINGS_HOME', ordering, GRAPH)
        assert result.fallback_reason == 'ambiguous_target'
        assert result.target_node_id is None
        assert result.score_gap == 0


def test_graph_synonyms_alone_change_runtime_selection():
    nodes = normalize_accessibility_tree((_node('alias', '보기설정'),))
    assert match_next_ui('S1_SETTINGS_HOME', nodes, GRAPH).target_node_id is None
    graph = replace(GRAPH, synonyms={**GRAPH.synonyms, 'display': (*GRAPH.synonyms['display'], '보기설정')})
    assert match_next_ui('S1_SETTINGS_HOME', nodes, graph).target_node_id == 'alias'


def test_resource_id_can_disambiguate_but_not_override_role():
    transitions = tuple(replace(t, selector=TargetSelector(resource_ids=('settings:id/display',)))
                        if t.transition_id == 'S1_TO_S2_DISPLAY_MENU' else t for t in GRAPH.transitions)
    graph = replace(GRAPH, transitions=transitions)
    nodes = (_node('a', '디스플레이'), replace(_node('b', '디스플레이'), resource_id='settings:id/display'))
    assert match_next_ui('S1_SETTINGS_HOME', normalize_accessibility_tree(nodes), graph).target_node_id == 'b'


def test_brightness_change_does_not_complete_font_task():
    turns = Turns()
    brightness = replace(_font_size_control_tree(slider_value=2)[-1], node_id='brightness', text='밝기')
    first_tree = (*_font_size_control_tree(slider_value=2), brightness)
    # Named font control is required when more than one slider is present.
    first_tree = tuple(replace(n, text='글자 크기') if n.node_id == 'font-slider' else n for n in first_tree)
    turns.send(first_tree)
    accepted = turns.send(first_tree, confirmation='accepted')
    after = tuple(replace(n, extras={'progress': 3}) if n.node_id == 'brightness' else n for n in first_tree)
    result = turns.send(after, event=turns.event(accepted, action=UserActionType.DRAG_COMPLETED))
    assert result.guide_command.command_type != CommandType.DONE
    assert not turns.session.task_completed


@pytest.mark.parametrize('problem', ['no_action', 'wrong_guide', 'wrong_target', 'old_observation', 'missing_value'])
def test_completion_requires_correlated_target_evidence(problem):
    turns = Turns()
    turns.send(_font_size_control_tree(slider_value=2), at=1000)
    accepted = turns.send(_font_size_control_tree(slider_value=2), at=1100, confirmation='accepted')
    event = turns.event(accepted, at=1200, action=UserActionType.DRAG_COMPLETED)
    if problem == 'no_action': event = None
    if problem == 'wrong_guide': event = replace(event, guide_command_id='another-guide')
    if problem == 'wrong_target': event = replace(event, target_node_id='brightness')
    tree = _font_size_control_tree(slider_value=None if problem == 'missing_value' else 3)
    result = turns.send(tree, at=1150 if problem == 'old_observation' else 1400, event=event)
    assert result.guide_command.command_type != CommandType.DONE
    assert not turns.session.task_completed


def test_pre_action_value_change_cannot_be_claimed_by_later_action():
    turns = Turns()
    turns.send(_font_size_control_tree(slider_value=2))
    accepted = turns.send(_font_size_control_tree(slider_value=2), confirmation='accepted')
    turns.send(_font_size_control_tree(slider_value=3))
    result = turns.send(_font_size_control_tree(slider_value=3),
        event=turns.event(accepted, action=UserActionType.DRAG_COMPLETED))
    assert result.guide_command.command_type != CommandType.DONE


def test_success_uses_original_baseline_and_survives_waiting_observation():
    turns = Turns()
    turns.send(_font_size_control_tree(slider_value=2), at=1000, applied_scale=1.0)
    accepted = turns.send(_font_size_control_tree(slider_value=2), at=1100, confirmation='accepted', applied_scale=1.0)
    waiting = turns.send(_font_size_control_tree(slider_value=2), at=1250, applied_scale=1.0,
        event=turns.event(accepted, at=1200, action=UserActionType.DRAG_COMPLETED))
    assert waiting.verification_result.status == Status.WAITING_FOR_OBSERVATION
    done = turns.send(_font_size_control_tree(slider_value=3), at=1800, applied_scale=1.15)
    assert done.verification_result.evidence == 'target_value_increased'
    assert done.guide_command.command_type == CommandType.DONE
    assert turns.session.task_completed
    assert turns.send(_font_size_control_tree(slider_value=3), at=2000, applied_scale=1.15).guide_command.command_type == CommandType.DONE


def test_active_window_filters_background_targets():
    nodes = (_node('foreground', '설정', clickable=False), _node('background', '디스플레이'))
    nodes = tuple(replace(n, window_id=1 if n.node_id == 'foreground' else 2) for n in nodes)
    normalized = normalize_accessibility_tree(nodes, active_window_id=1)
    assert match_next_ui('S1_SETTINGS_HOME', normalized, GRAPH).target_node_id is None


def test_resource_name_cannot_make_other_app_a_launcher():
    node = replace(_node('fake', '설정'), package_name='com.other.app', resource_id='other:id/app_icon')
    assert match_next_ui('S0_UNKNOWN', normalize_accessibility_tree((node,)), GRAPH).target_node_id is None


def test_same_observation_id_cannot_supply_new_completion_evidence():
    turns = Turns()
    turns.send(_font_size_control_tree(slider_value=2), at=1000)
    accepted = turns.send(_font_size_control_tree(slider_value=2), at=1100, confirmation='accepted')
    turns.send(_font_size_control_tree(slider_value=2), at=1300, observation='same-observation',
               event=turns.event(accepted, at=1200, action=UserActionType.DRAG_COMPLETED))
    stale = turns.send(_font_size_control_tree(slider_value=3), at=2000, observation='same-observation')
    assert not stale.verification_result.task_completed
    assert stale.verification_result.status == Status.WAITING_FOR_OBSERVATION
