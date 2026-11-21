"""Both arms share native actions/verifiers; only LLM subgoal ordering differs."""
from copy import deepcopy
from dataclasses import replace
import json
from types import SimpleNamespace
from unittest.mock import Mock

import httpx
import pytest
from sqlalchemy import select

from app.clients.checkpoint import capture, restore
from app.services.settings_experiment import SettingsExperimentResolver
from app.services.settings_planner import GeminiSettingsPlanner, SettingsPlanner, catalogue
from tests.test_settings_stability_matrix import Run, GOALS
from tests.test_screen_jev import scene


class Planner:
    def __init__(self):
        self.calls = []

    def plan(self, **kwargs):
        self.calls.append(deepcopy(kwargs))
        return {'subgoals': [t['id'] for t in kwargs['targets'] if t['id'] not in kwargs['completed']]}, {'called': True, 'status': 'SUCCESS'}


def test_all_existing_settings_graphs_have_reviewed_completion_vocabulary():
    from app.task_graphs import approved_graphs
    from app.services.settings_planner import completion_key, supports_graph
    for graph in approved_graphs().values():
        if not supports_graph(graph):
            continue
        targets, bindings = catalogue(graph)
        assert completion_key(graph) in bindings, graph.definition.goal_type
        assert all(edge in {e.transition_id for e in graph.transitions} for edges in bindings.values() for edge in edges)


def test_gemini_plans_wallpaper_navigation_and_manual_handoff():
    from app.task_graphs import approved_graphs
    graph = approved_graphs()['settings.wallpaper.change']
    targets, _ = catalogue(graph)
    steps = [t['id'] for t in targets]
    def sender(payload, **kwargs):
        assert 'end with complete_setting' in payload['contents'][0]['parts'][0]['text']
        return httpx.Response(200, json={'candidates':[{'finishReason':'STOP','content':{'parts':[{'text':json.dumps({'subgoals':steps})}]}}]})
    plan, diagnostic = GeminiSettingsPlanner(sender).plan(goal=graph.definition.goal_type,
        targets=targets, current_page='S1_SETTINGS_HOME', visible_targets=[], completed=[], failures=[])
    assert plan['subgoals'][-1] == 'complete_setting'
    assert diagnostic['status'] == 'SUCCESS'


@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-key')
    monkeypatch.setenv('JEV_MODEL', 'jev-1.14.0')


@pytest.mark.parametrize('setting,direction', GOALS)
@pytest.mark.parametrize('arm', ['graph', 'llm'])
def test_each_arm_routes_and_requires_native_action_and_effect(setting, direction, arm):
    from ai_agent.runtime.types import UserActionEvent, UserActionType
    run = Run(setting, direction)
    planner = Planner()
    wrapper = SettingsExperimentResolver(run.resolver, planner)
    wrapper.prepare(None, arm, 'e')
    run.engine.screen_resolver = wrapper
    approved = run.route()
    assert bool(planner.calls) == (arm == 'llm')
    before = run.engine.session_store.get('s')
    run.observe(run.control)
    assert not run.engine.session_store.get('s').task_completed
    assert len(planner.calls) <= 1
    sign = 1 if direction == 'increase' else -1
    changed = (run.control[0], replace(run.control[1], extras={'progress': 3 + sign, 'min':0, 'max':7}))
    key = {'font_size':'font_scale', 'brightness':'screen_brightness', 'volume':'media_volume'}[setting]
    event = UserActionEvent(UserActionType.DRAG_COMPLETED, approved.guide_command.guide_command_id,
        'target', occurred_at_ms=run.at+500, event_id='changed', episode_id='e', session_id='s')
    # Generate a new approved guide after waiting; use its binding.
    pending = run.engine.session_store.get('s').pending_attempt
    event = replace(event, guide_command_id=pending['guide_command_id'])
    run.observe(changed, event=event, applied={**run.applied, key:run.applied[key] + sign * (.2 if setting=='font_size' else 1)})
    assert run.engine.session_store.get('s').task_completed
    assert len(planner.calls) == (1 if arm == 'llm' else 0)


def test_planner_failure_never_falls_back_to_graph():
    graph, edge, node, goal = scene('font_size')
    inner = Mock()
    planner = Mock()
    planner.plan.return_value = (None, {'called':True, 'status':'UNKNOWN', 'reason_code':'provider_error'})
    wrapper = SettingsExperimentResolver(inner, planner)
    wrapper.prepare(None, 'llm', 'e')
    assert wrapper.resolve(nodes=(node,), graph=graph, goal=goal) is None
    inner.resolve.assert_not_called()
    assert wrapper.state['planner_calls'] == 1


def test_empty_observation_never_calls_planner():
    graph, _, _, goal = scene('font_size')
    inner, planner = Mock(), Mock()
    wrapper = SettingsExperimentResolver(inner, planner)
    wrapper.prepare(None, 'llm', 'e')
    assert wrapper.resolve(nodes=(), raw_nodes=(), graph=graph, goal=goal) is None
    planner.plan.assert_not_called()
    inner.resolve.assert_not_called()


def test_credential_screen_never_calls_either_model():
    from ai_agent.runtime.types import AccessibilityNode
    graph, _, node, goal = scene('font_size')
    inner, planner = Mock(), Mock()
    wrapper = SettingsExperimentResolver(inner, planner)
    wrapper.prepare(None, 'llm', 'e')
    raw = (AccessibilityNode('secret', package_name='com.android.settings', class_name='android.widget.EditText',
        text=None, bounds_in_screen=(0,0,200,100), extras={'is_password':True}),)
    assert wrapper.resolve(nodes=(node,), raw_nodes=raw, graph=graph, goal=goal) is None
    planner.plan.assert_not_called()
    inner.resolve.assert_not_called()


def test_checkpoint_pins_arm_and_preserves_plan_without_new_call():
    run = Run('font_size', 'increase')
    planner = Planner()
    wrapper = SettingsExperimentResolver(run.resolver, planner)
    wrapper.prepare(None, 'llm', 'e')
    run.engine.screen_resolver = wrapper
    run.edge = run.home_edge
    run.observe(run.home)
    run.engine.session_store.update('s', settings_experiment=wrapper.state)
    restored = restore(capture(run.engine.session_store, 's', run.graph)).get('s')
    second = SettingsExperimentResolver(Mock(), Mock())
    second.prepare(restored, 'graph', 'e')
    assert second.arm == 'llm'
    assert second.state['plan'] == wrapper.state['plan']
    assert second.state['planner_calls'] == 1


def test_two_distinct_verified_failures_request_replan_without_erasing_completed():
    graph, edge, _, _ = scene('font_size')
    from app.services.settings_planner import navigation_key
    wrapper=SettingsExperimentResolver(Mock(),Mock())
    completed=navigation_key('S2_DISPLAY_MENU')
    wrapper.state={'completed':[completed],'failed_count':0,'failures':[]}
    failed=SimpleNamespace(status=SimpleNamespace(value='NO_CHANGE'),count_attempt=True)
    session=SimpleNamespace(pending_transition_id=edge.transition_id,pending_attempt={'attempt_id':'first'})
    wrapper.advance(session,failed,{edge.transition_id:edge})
    wrapper.advance(session,failed,{edge.transition_id:edge})
    assert wrapper.state['failed_count']==1
    session.pending_attempt={'attempt_id':'second'}
    wrapper.advance(session,failed,{edge.transition_id:edge})
    assert wrapper.state['replan_requested']
    assert wrapper.state['completed']==[completed]


def test_declared_llm_trial_cannot_silently_run_when_experiment_disabled(monkeypatch):
    from app.clients.runtime_agent import RuntimeAgentAdapter
    monkeypatch.setenv('CLICK_SETTINGS_EXPERIMENT_ENABLED','0')
    adapter=RuntimeAgentAdapter()
    request=SimpleNamespace(session_id='s',device_context=SimpleNamespace(
        capabilities=SimpleNamespace(settings_experiment_arm='llm')))
    with pytest.raises(ValueError,match='requires enabled pilot'):
        adapter.handle_turn(request)


def test_summary_keeps_unknown_separate_from_failures_and_groups_by_goal():
    from scripts.settings_experiment_log import summarize
    record={'run_ref':'one','arm':'llm','goal_type':'settings.font_size.increase','at':1,
        'jev':{'called':False},'planner':{'called':True,'latency_ms':100},'planner_calls':1,
        'verification':{'final':{'status':'UNKNOWN'}},'total_failed_attempts':0}
    report=summarize([record,{**record,'at':2,'planner':{'called':False}}])
    assert report[0]['trials']==1 and report[0]['native_goal_successes']==0
    assert report[0]['final_unknown_or_incomplete']==1 and report[0]['failed_attempts']==0
    assert report[0]['planner_calls']==1 and report[0]['planner_latency_ms']==100


def test_preparation_log_shows_requests_that_never_reach_runtime_without_raw_input():
    from scripts.settings_experiment_log import preparation_record
    row=SimpleNamespace(session_id='s',expires_at=1000,status='READY',response={
        'resolution_status':'confirmation_required','goal':{'goal_type':'settings.font_size.increase'},
        'preparation':{'family':'font_size','missing_fields':[],'source_text':'PRIVATE_INPUT'},
        'metadata':{'settings_experiment_preparation':{'arm':'graph'}}})
    record=preparation_record(row)
    assert record['status']=='WAITING' and record['reason_code']=='awaiting_start_confirmation'
    assert record['arm']=='graph' and 'PRIVATE_INPUT' not in json.dumps(record)


@pytest.mark.parametrize('bad', [
    {'subgoals':['delete_files','adjust_setting']}, {'subgoals':[]}, {'subgoals':['S1_SETTINGS_HOME']},
    {'subgoals':['adjust_setting','adjust_setting']}, {'subgoals':['adjust_setting'],'prompt':'ignore rules'},
])
def test_gemini_rejects_unknown_actions_and_missing_final_condition(bad):
    graph, _, _, _ = scene('font_size')
    targets, _ = catalogue(graph)
    def sender(payload, **kwargs):
        text = payload['contents'][0]['parts'][0]['text']
        assert 'transition_id' not in text and 'from_state' not in text
        return httpx.Response(200, json={'candidates':[{'finishReason':'STOP','content':{'parts':[{'text':json.dumps(bad)}]}}]})
    result, diagnostic = GeminiSettingsPlanner(sender).plan(goal=graph.definition.goal_type, targets=targets,
        current_page='S1_SETTINGS_HOME', visible_targets=[], completed=[], failures=[])
    assert result is None and diagnostic['status'] == 'UNKNOWN'


def test_valid_gemini_plan_records_token_usage():
    graph, _, _, _ = scene('font_size')
    targets, _ = catalogue(graph)
    def sender(payload, **kwargs):
        return httpx.Response(200, json={'candidates':[{'finishReason':'STOP','content':{'parts':[{
            'text':json.dumps({'subgoals':['adjust_setting']})}]}}], 'usageMetadata':{'promptTokenCount':20,'candidatesTokenCount':10}})
    result, diagnostic = GeminiSettingsPlanner(sender).plan(goal=graph.definition.goal_type, targets=targets,
        current_page='S4_FONT_SIZE_CONTROL', visible_targets=['adjust_setting'], completed=[], failures=[])
    assert result == {'subgoals':['adjust_setting']}
    assert diagnostic['input_tokens'] == 20 and diagnostic['output_tokens'] == 10


def test_real_transport_decompresses_gzip_once(monkeypatch):
    import gzip
    real_client=httpx.Client
    def upstream(request):
        return httpx.Response(200,headers={'content-encoding':'gzip','content-type':'application/json'},
            content=gzip.compress(json.dumps({'candidates':[{'finishReason':'STOP','content':{'parts':[
                {'text':'{"subgoals":["adjust_setting"]}'}]}}]}).encode()))
    monkeypatch.setenv('GEMINI_API_KEY','test-only-key')
    monkeypatch.setattr(httpx,'Client',lambda **kw:real_client(transport=httpx.MockTransport(upstream),**kw))
    plan,info=planner_request(None)
    assert plan and info['status']=='SUCCESS' and len(info['attempts'])==1


def test_model_override_is_explicit_and_recorded(monkeypatch):
    monkeypatch.setenv('GEMINI_SETTINGS_MODEL','gemini-3.5-flash')
    sender=Mock(return_value=httpx.Response(503,json={'error':{'status':'UNAVAILABLE'}}))
    monkeypatch.setattr('app.services.settings_planner.sleep',lambda _:None)
    plan,info=planner_request(sender)
    assert plan is None and info['model']=='gemini-3.5-flash'
    assert sender.call_count==3  # All retries use the same configured model.


def planner_request(sender):
    graph, _, _, _ = scene('font_size')
    return GeminiSettingsPlanner(sender).plan(goal=graph.definition.goal_type, targets=catalogue(graph)[0],
        current_page='settings', visible_targets=[], completed=[], failures=[])


def test_transient_provider_error_retries_then_validates(monkeypatch):
    monkeypatch.setattr('app.services.settings_planner.sleep', lambda _: None)
    sender = Mock(side_effect=[httpx.Response(503, json={'error':{'code':503,'status':'UNAVAILABLE',
        'message':'This model is currently experiencing high demand.'}}),
        httpx.Response(200, json={'candidates':[{'finishReason':'STOP','content':{'parts':[
            {'text':'{"subgoals":["adjust_setting"]}'}]}}]})])
    plan, info = planner_request(sender)
    assert plan and info['status'] == 'SUCCESS' and sender.call_count == 2
    assert info['attempts'][0]['provider_cause'] == 'model_high_demand'
    assert 'failure_kind' not in info


@pytest.mark.parametrize('code', [400,401,403,404])
def test_permanent_provider_error_is_not_retried_or_leaked(code):
    sender=Mock(return_value=httpx.Response(code,json={'error':{'status':'PERMISSION_DENIED',
        'message':'PRIVATE_SCREEN API_SECRET'}}))
    plan, info=planner_request(sender)
    assert plan is None and sender.call_count == 1
    assert info['failure_kind'] == 'provider' and not info['retryable']
    assert 'PRIVATE_SCREEN' not in json.dumps(info) and 'API_SECRET' not in json.dumps(info)


def test_retry_after_beyond_deadline_does_not_sleep(monkeypatch):
    sleep=Mock()
    monkeypatch.setattr('app.services.settings_planner.sleep',sleep)
    sender=Mock(return_value=httpx.Response(429,headers={'retry-after':'60'},json={
        'error':{'code':429,'status':'RESOURCE_EXHAUSTED','message':'Quota exceeded'}}))
    plan, info=planner_request(sender)
    assert plan is None and sender.call_count == 1
    assert info['retry_after_seconds'] == 60 and info['provider_cause'] == 'quota_reported'
    sleep.assert_not_called()


def test_transport_retries_are_bounded(monkeypatch):
    monkeypatch.setattr('app.services.settings_planner.sleep',lambda _:None)
    sender=Mock(side_effect=httpx.ReadTimeout('SECRET'))
    plan, info=planner_request(sender)
    assert plan is None and sender.call_count == 3
    assert info['failure_kind'] == 'provider' and 'SECRET' not in json.dumps(info)


def test_daily_quota_is_not_retried():
    sender=Mock(return_value=httpx.Response(429,json={'error':{'status':'RESOURCE_EXHAUSTED',
        'message':'Generate content requests per day limit exceeded on Free Tier.'}}))
    plan, info=planner_request(sender)
    assert plan is None and sender.call_count == 1
    assert info['provider_cause'] == 'daily_quota_exceeded' and not info['retryable']


def test_provider_outages_do_not_exhaust_plan_budget_or_drop_replan():
    graph, _, node, goal=scene('font_size')
    planner, inner=Mock(), Mock()
    planner.plan.return_value=(None,{'called':True,'failure_kind':'provider','reason_code':'provider_unavailable'})
    wrapper=SettingsExperimentResolver(inner,planner)
    wrapper.prepare(None,'llm','e')
    for _ in range(5):
        assert wrapper.resolve(nodes=(node,),graph=graph,goal=goal) is None
    assert wrapper.state['planner_calls'] == 5 and wrapper.state['planning_attempts'] == 0
    assert wrapper.state['provider_failures'] == 5
    wrapper.state.update(plan={'subgoals':['adjust_setting']},replan_requested=True)
    assert wrapper.resolve(nodes=(node,),graph=graph,goal=goal) is None
    assert wrapper.state['replan_requested']
    inner.resolve.assert_not_called()


def test_invalid_plans_still_exhaust_plan_budget():
    graph, _, node, goal=scene('font_size')
    planner=Mock()
    planner.plan.return_value=(None,{'called':True,'failure_kind':'plan','reason_code':'invalid_plan'})
    wrapper=SettingsExperimentResolver(Mock(),planner)
    wrapper.prepare(None,'llm','e')
    for _ in range(4):
        assert wrapper.resolve(nodes=(node,),graph=graph,goal=goal) is None
    assert planner.plan.call_count == 3
    assert wrapper.turn['planner']['reason_code'] == 'planning_budget_exhausted'


@pytest.mark.parametrize('arm', ['graph', 'llm'])
def test_http_persists_arm_plan_and_three_verdicts(monkeypatch, arm):
    from app.main import create_app
    from tests.helpers import authenticated_client
    from tests.test_typed_agent_contract import start_payload
    from app.services.screen_jev import JevScreenResolver
    from tests.test_screen_jev import sender_for
    from app.task_graphs import approved_graphs
    from app.models.runtime_event import RuntimeEvent
    monkeypatch.setenv('CLICK_SETTINGS_EXPERIMENT_ENABLED', '1')
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER', 'jev')
    planner = Planner()
    monkeypatch.setattr(SettingsPlanner, 'plan', lambda self, **kw: planner.plan(**kw))
    graph = approved_graphs()['settings.font_size.increase']
    edge = next(e for e in graph.transitions if e.from_state == 'S1_SETTINGS_HOME')
    monkeypatch.setattr(JevScreenResolver, '_post', staticmethod(sender_for(edge)))
    app = create_app()
    with authenticated_client(app) as client:
        body = start_payload()
        body['device_context']['capabilities'] = {'settings_experiment_arm':arm}
        body['accessibility_tree']['captured_at_ms'] = 1000
        body['accessibility_tree']['observation_id'] = 'fresh'
        response = client.post('/api/v1/agent/commands', json=body)
        assert response.status_code == 200, response.text
        data = response.json()
        info = data['metadata']['settings_experiment']
        assert info['arm'] == arm
        assert set(info['verification']) == {'action','subgoal','final'}
        assert info['verification']['final']['status'] == 'UNKNOWN'
        assert bool(planner.calls) == (arm == 'llm')
        with app.state.session_factory() as db:
            event = db.scalars(select(RuntimeEvent).where(RuntimeEvent.session_id == 'typed-session')).first()
            assert event.payload['settings_experiment']['arm'] == arm
        text = json.dumps(info)
        assert '"display"' not in text  # Native node ID does not enter the diagnostic.


def qwen_reply(plan, model='qwen/qwen3.8-flash'):
    return httpx.Response(200, json={
        'status': 'completed', 'model': model,
        'output': [{'type': 'message', 'role': 'assistant',
                    'content': [{'type': 'output_text', 'text': json.dumps(plan)}]}],
        'usage': {'input_tokens': 300, 'output_tokens': 18},
    })


def test_qwen_hands_only_the_active_subgoal_to_jev_on_the_app_route(monkeypatch):
    from app.main import create_app
    from app.services.screen_jev import JevScreenResolver
    from app.services.settings_planner import navigation_key
    from app.task_graphs import approved_graphs
    from tests.helpers import authenticated_client
    from tests.test_screen_jev import sender_for
    from tests.test_typed_agent_contract import start_payload

    monkeypatch.setenv('CLICK_SETTINGS_EXPERIMENT_ENABLED', '1')
    monkeypatch.setenv('CLICK_SETTINGS_PLANNER_PROVIDER', 'openrouter')
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER', 'jev')
    monkeypatch.setenv('OPENROUTER_API_KEY', 'test-qwen')
    monkeypatch.delenv('GEMINI_API_KEY', raising=False)
    graph = approved_graphs()['settings.font_size.increase']
    edge = next(t for t in graph.transitions if t.from_state == 'S1_SETTINGS_HOME')
    steps = [t['id'] for t in catalogue(graph)[0] if t['id'] != navigation_key('S1_SETTINGS_HOME')]
    first = navigation_key(edge.to_state)
    assert steps[0] == first
    def qwen(self, payload, token, diagnostic):
        assert token == 'test-qwen' and payload['model'] == 'qwen/qwen3.8-flash'
        assert list(payload['text']['format']['schema']['properties']) == ['subgoals']
        assert 'accessibility_tree' not in payload['input'] and edge.transition_id not in payload['input']
        diagnostic.update(called=True, attempts=[{'attempt': 1, 'http_status': 200}])
        return qwen_reply({'subgoals': steps})
    planner = Mock(side_effect=qwen)
    monkeypatch.setattr(SettingsPlanner, 'request', lambda self, *args: planner(self, *args))
    jev = Mock(side_effect=sender_for(edge))
    monkeypatch.setattr(JevScreenResolver, '_post', staticmethod(jev))
    with authenticated_client(create_app()) as client:
        body = start_payload()
        body['device_context']['capabilities'] = {'settings_experiment_arm': 'llm'}
        tree = body['accessibility_tree']
        tree.update(captured_at_ms=1000, observation_id='settings-home')
        tree['nodes'][0]['class_name'] = 'android.widget.TextView'
        tree['nodes'].insert(0, dict(node_id='title', text='설정', clickable=False,
            package_name='com.android.settings', class_name='android.widget.TextView', bounds_in_screen=[20,20,900,90]))
        response = client.post('/api/v1/agent/commands', json=body)
    assert response.status_code == 200
    result = response.json()
    trace = result['metadata']['settings_experiment']
    planner.assert_called_once()
    assert jev.call_count == 1, trace
    assert jev.call_args.args[0]['state']['subgoal']['id'] == first
    assert trace['plan_source'] == 'qwen' and trace['planner_model'] == 'qwen/qwen3.8-flash'
    assert trace['active_subgoal'] == first and trace['jev']['called']
    assert result['guide_command']['target_node_id'] == 'display'


@pytest.mark.parametrize('reply', [
    qwen_reply({'subgoals': ['adjust_setting'], 'target_id': 'display'}),
    qwen_reply({'subgoals': ['tap:display', 'adjust_setting']}),
    qwen_reply({'subgoals': ['adjust_setting']}, model='other'),
    httpx.Response(503, json={'error': {'message': 'PRIVATE_PROVIDER_MESSAGE'}}),
])
def test_qwen_invalid_plan_or_outage_cannot_select_a_ui_target(monkeypatch, reply):
    monkeypatch.setenv('CLICK_SETTINGS_PLANNER_PROVIDER', 'openrouter')
    graph, _, node, goal = scene('font_size')
    send, jev = Mock(return_value=reply), Mock()
    resolver = SettingsExperimentResolver(jev, SettingsPlanner(send))
    resolver.prepare(None, 'llm', 'e')
    assert resolver.resolve(nodes=(node,), graph=graph, goal=goal) is None
    send.assert_called_once()
    jev.resolve.assert_not_called()
    assert resolver.turn['planner']['failure_kind'] == ('provider' if reply.status_code == 503 else 'plan')
    assert 'PRIVATE_PROVIDER_MESSAGE' not in str(resolver.turn)


def test_qwen_transport_uses_only_the_router_endpoint_and_credential(monkeypatch):
    from app.services.openai_responses import OPENROUTER_ENDPOINT
    monkeypatch.setenv('CLICK_SETTINGS_PLANNER_PROVIDER', 'openrouter')
    monkeypatch.setenv('GEMINI_API_KEY', 'test-other-key')
    monkeypatch.delenv('OPENROUTER_API_KEY', raising=False)
    graph, _, _, _ = scene('font_size')
    params = dict(goal=graph.definition.goal_type, targets=catalogue(graph)[0], current_page='settings',
                  visible_targets=[], completed=[], failures=[])
    def upstream(req):
        assert str(req.url) == OPENROUTER_ENDPOINT and req.headers['Authorization'] == 'Bearer test-router'
        assert 'x-goog-api-key' not in req.headers
        return qwen_reply({'subgoals': ['adjust_setting']})
    transport = Mock(side_effect=upstream)
    real_client = httpx.Client
    monkeypatch.setattr(httpx, 'Client', lambda **kw: real_client(transport=httpx.MockTransport(transport), **kw))
    planner = SettingsPlanner()
    assert planner.plan(**params)[1]['reason_code'] == 'missing_api_key'
    transport.assert_not_called()
    monkeypatch.setenv('OPENROUTER_API_KEY', 'test-router')
    plan, info = planner.plan(**params)
    assert plan == {'subgoals': ['adjust_setting']} and info['output_tokens'] == 18
    transport.assert_called_once()
