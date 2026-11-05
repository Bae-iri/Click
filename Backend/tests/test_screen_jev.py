import httpx
import pytest
from dataclasses import replace

from app.services.screen_jev import JevScreenResolver
from app.clients.runtime_agent import RuntimeAgentAdapter
from app.task_graphs import approved_graphs
TASK_GRAPHS = approved_graphs()
from ai_agent.runtime.types import NormalizedNode, TypedGoal, AgentTurnRequest, ActionType, AccessibilityNode, DeviceContext
from ai_agent.runtime.engine import ClickAgentEngine


@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv("TYPESAFE_API_KEY", "test-only-key")
    monkeypatch.setenv("JEV_MODEL", "jev-1.14.0")


def scene(setting):
    graph = next(g for g in TASK_GRAPHS.values() if g.definition.required_slots.get("setting") == setting
                 and g.definition.required_slots.get("direction") == "increase")
    transition = next(t for t in graph.transitions if t.target_role == "slider")
    package = (transition.selector.package_candidates or graph.states[transition.from_state].package_candidates)[0]
    from ai_agent.runtime.keywords import normalize_text
    node = NormalizedNode("real-private-id", normalize_text(transition.target_keywords[0]), "slider", bounds=(10,100,300,140),
        actionable=True, package_name=package, value=3, range_min=0, range_max=10)
    goal = TypedGoal(goal_type=graph.definition.goal_type, target_app=graph.definition.target_app,
        task_type=graph.definition.task_type, slots=graph.definition.required_slots, source_text="",
        confidence=1, task_id=graph.definition.task_id, is_supported=True, required_slots_filled=True)
    return graph, transition, node, goal


def sender_for(transition, choice_override=None, mutate=None):
    def send(payload, **kwargs):
        criteria = payload["questions"]["screen"]["criteria"]
        assert "real-private-id" not in str(payload)
        choice = choice_override or next((k for k,v in criteria.items() if isinstance(v,dict)
                                         and v["transition"] == transition.transition_id), "insufficient_evidence")
        answer = {"type":"choice", "choice":choice, "confidence":1,
            "probabilities": {k:float(k==choice) for k in criteria}}
        result = {"model":payload["model"], "answers":{"screen":answer}}
        if mutate:
            mutate(result)
        return httpx.Response(200,json=result)
    return send


@pytest.mark.parametrize("setting", ["brightness", "font_size", "volume"])
def test_approved_slider_is_candidate_and_coordinates_are_observed(setting):
    graph,t,n,goal=scene(setting)
    match=JevScreenResolver(sender_for(t)).resolve(nodes=(n,),graph=graph,goal=goal)
    assert match.transition_id == t.transition_id
    assert match.target_node_id == n.node_id
    assert match.target_bounds == n.bounds
    assert match.state_id == t.from_state
    assert JevScreenResolver(sender_for(t)).resolve(nodes=(replace(n,enabled=False),),graph=graph,goal=goal) is None


@pytest.mark.parametrize("choice", ["wait_for_result", "insufficient_evidence", "invented"])
def test_abstention_and_unknown_choice(choice):
    graph,t,n,goal=scene("brightness")
    assert JevScreenResolver(sender_for(t,choice)).resolve(nodes=(n,),graph=graph,goal=goal) is None


def test_failure_and_model_mismatch():
    graph,t,n,goal=scene("font_size")
    def fail(*args,**kwargs):
        raise httpx.ConnectError("offline")
    for sender in (fail, lambda *a,**kw: httpx.Response(500),
                   sender_for(t,mutate=lambda r:r.update(model="jev-latest"))):
        assert JevScreenResolver(sender).resolve(nodes=(n,),graph=graph,goal=goal) is None


@pytest.mark.parametrize("setting", ["brightness", "font_size", "volume"])
def test_runtime_reaches_selected_step_and_changed_screen_abstains(setting):
    graph,t,n,goal=scene(setting)
    resolver=JevScreenResolver(sender_for(t))
    engine=ClickAgentEngine(task_graphs={goal.goal_type:graph,goal.task_id:graph},screen_resolver=resolver)
    node=AccessibilityNode(node_id=n.node_id,text=n.label,role="slider",class_name="android.widget.SeekBar",
        package_name=n.package_name,bounds_in_screen=n.bounds,extras={"value":3,"min":0,"max":10})
    request=AgentTurnRequest(request_id="r1",session_id="s",typed_goal=goal,raw_goal=None,
        device_context=DeviceContext(platform="android"),accessibility_tree=(node,),observation_id="o1",captured_at_ms=1000)
    result=engine.handle_turn(request)
    assert result.current_state_id == t.from_state
    assert result.guide_command.target_node_id == n.node_id
    result=engine.handle_turn(replace(request,request_id="r2",observation_id="o2",captured_at_ms=2000,
        accessibility_tree=(replace(node,enabled=False),)))
    assert result.guide_command.action_type == ActionType.OBSERVE_ONLY
    assert result.guide_command.target_node_id is None
    assert result.guide_command.render_policy["fallback_action"]["type"] == "request_fresh_observation"


def test_provider_is_independent(monkeypatch):
    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "jev")
    assert isinstance(RuntimeAgentAdapter().runtime.screen_resolver,JevScreenResolver)
    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "local")
    assert RuntimeAgentAdapter().runtime.screen_resolver is None


@pytest.mark.parametrize("setting", ["brightness", "font_size", "volume"])
def test_action_waits_and_unchanged_failure_does_not_repeat(setting):
    from ai_agent.runtime import UserActionEvent, UserActionType
    graph,t,n,goal=scene(setting)
    engine=ClickAgentEngine(task_graphs={goal.task_id:graph},screen_resolver=JevScreenResolver(sender_for(t)))
    node=AccessibilityNode(node_id=n.node_id,text=n.label,role="slider",class_name="android.widget.SeekBar",
        package_name=n.package_name,bounds_in_screen=n.bounds,extras={"value":3,"min":0,"max":10})
    request=AgentTurnRequest(request_id="r1",session_id="s",typed_goal=goal,raw_goal=None,
        device_context=DeviceContext(platform="android"),accessibility_tree=(node,),
        observation_id="o1",captured_at_ms=1000,user_confirmation="accepted")
    first=engine.handle_turn(request)
    event=UserActionEvent(UserActionType.DRAG_COMPLETED,first.guide_command.guide_command_id,
        n.node_id,occurred_at_ms=1100,event_id="e1")
    waiting=engine.handle_turn(replace(request,request_id="r2",observation_id="o2",
        captured_at_ms=1200,user_action_event=event))
    assert waiting.guide_command.action_type == ActionType.OBSERVE_ONLY
    for i in range(3,6):
        result=engine.handle_turn(replace(request,request_id=f"r{i}",observation_id=f"o{i}",
            captured_at_ms=1000*i,user_action_event=None))
        assert result.guide_command.action_type == ActionType.OBSERVE_ONLY
        assert result.guide_command.target_node_id is None


@pytest.mark.parametrize("change", [dict(package_name="unrelated.app"),dict(bounds=None),dict(role="button")])
def test_wrong_target_never_enters_slider_candidates(change):
    graph,t,n,goal=scene("brightness")
    assert JevScreenResolver(sender_for(t)).resolve(nodes=(replace(n,**change),),graph=graph,goal=goal) is None


def test_reviewed_route_priorities_apply_even_when_model_prefers_old_route():
    graph, transition, node, goal = scene("font_size")
    primary = replace(transition, priority=0)
    alternative = replace(transition, transition_id="ALTERNATE_SLIDER_ROUTE", priority=10)
    graph = replace(graph, transitions=tuple(primary if t == transition else t for t in graph.transitions) + (alternative,))
    node = replace(node, label=transition.target_keywords[0])
    selected = JevScreenResolver(sender_for(alternative)).resolve(nodes=(node,), graph=graph, goal=goal)
    assert selected.transition_id == primary.transition_id
    # A priority-only graph release changes the runtime decision without changing selectors.
    graph = replace(graph, transitions=tuple(replace(t, priority=20) if t == primary else
        replace(t, priority=0) if t == alternative else t for t in graph.transitions))
    selected = JevScreenResolver(sender_for(primary)).resolve(nodes=(node,), graph=graph, goal=goal)
    assert selected.transition_id == alternative.transition_id


def test_route_priority_never_overrides_candidate_geometry_validation():
    graph, transition, node, goal = scene("font_size")
    primary = replace(transition, priority=0, selector=replace(transition.selector, resource_ids=("outside",)))
    alternative = replace(transition, transition_id="VISIBLE_ROUTE", priority=10)
    graph = replace(graph, transitions=tuple(primary if t == transition else t for t in graph.transitions) + (alternative,))
    node = replace(node, label=transition.target_keywords[0])
    outside = replace(node, node_id="outside", resource_id="outside", bounds=(500, 100, 700, 140))
    selected = JevScreenResolver(sender_for(alternative)).resolve(nodes=(node, outside), graph=graph,
        goal=goal, screen_bounds=(0, 0, 400, 800))
    assert selected.transition_id == alternative.transition_id
    assert selected.target_node_id == node.node_id


def test_samsung_rows_preserve_menu_identity_and_independent_page_title():
    from ai_agent.runtime.normalizer import normalize_accessibility_tree
    graph, _, _, goal = scene('font_size')
    entry = next(t for t in graph.transitions if t.from_state == 'S1_SETTINGS_HOME')
    raw = (
        AccessibilityNode(node_id='list', scrollable=True, class_name='androidx.recyclerview.widget.RecyclerView',
            package_name='com.android.settings', bounds_in_screen=(0, 100, 400, 800)),
        AccessibilityNode(node_id='title', text='설정', class_name='android.widget.TextView',
            package_name='com.android.settings', bounds_in_screen=(0, 0, 400, 80)),
        AccessibilityNode(node_id='row', parent_id='list', clickable=True, class_name='android.widget.LinearLayout',
            package_name='com.android.settings', bounds_in_screen=(0, 200, 400, 400)),
        AccessibilityNode(node_id='name', parent_id='row', text='디스플레이',
            resource_id='android:id/title', package_name='com.android.settings',
            bounds_in_screen=(20, 210, 380, 260)),
        AccessibilityNode(node_id='summary', parent_id='row', text='밝기 · 편안하게 화면 보기 · 내비게이션 바',
            resource_id='android:id/summary', package_name='com.android.settings',
            bounds_in_screen=(20, 270, 380, 350)),
    )
    def send(payload, **kwargs):
        nodes = {n['id']: n for n in payload['state']['nodes']}
        candidate = next(c for c in payload['questions']['screen']['criteria'].values()
                         if isinstance(c, dict) and c['transition'] == entry.transition_id)
        row = nodes[candidate['node']]
        assert row['label_parts'] == ['디스플레이']
        assert row['structure_role'] == 'control'
        assert row['actionable'] is True
        assert row['label'] == '디스플레이'
        assert any(n['label'] == '설정' and n['structure_role'] == 'screen_text' for n in nodes.values())
        assert not any(n['structure_role'] == 'description' for n in nodes.values())
        assert candidate['current_page'] == graph.states[entry.from_state].name
        assert candidate['destination_page'] == graph.states[entry.to_state].name
        assert '디스플레이' in candidate['target_names']
        return sender_for(entry)(payload, **kwargs)
    resolver = JevScreenResolver(send)
    selected = resolver.resolve(nodes=normalize_accessibility_tree(raw), raw_nodes=raw, graph=graph, goal=goal)
    assert selected.target_node_id == 'row'
    assert selected.target_bounds == (0, 200, 400, 400)
    assert resolver.last_diagnostics['grounded_candidate_count'] > 0


def test_low_confidence_reports_rejected_step_without_screen_text(monkeypatch):
    monkeypatch.setenv('JEV_MIN_CONFIDENCE', '0.8')
    graph, transition, node, goal = scene('font_size')
    resolver = JevScreenResolver(sender_for(transition, mutate=lambda r:
        r['answers']['screen'].update(confidence=0.4)))
    assert resolver.resolve(nodes=(node,), graph=graph, goal=goal) is None
    diagnostic = resolver.last_diagnostics
    assert diagnostic['status'] == 'low_confidence'
    assert diagnostic['confidence'] == 0.4
    assert diagnostic['minimum_confidence'] == 0.8
    assert diagnostic['chosen_transition'] == transition.transition_id
    assert diagnostic['chosen_state'] == transition.from_state
    assert node.node_id not in str(diagnostic)
    assert node.label not in str(diagnostic)
