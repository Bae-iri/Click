"""Shared route analysis uses consented runtime evidence, not user profiles."""
from copy import deepcopy
from datetime import datetime, timezone, timedelta
import time

import pytest
from sqlalchemy import select, func
from app.main import create_app
from app.models.agent_session import AgentSession
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.models.task_graph import ApprovedTaskGraph
from app.models.user import Consent
from app.repositories.runtime_events import device_cohort, cleanup
from app.task_graphs import approved_graphs
from scripts.agent2 import propose_routes, stored_proposal, ROUTE_CONSUMER
from tests.helpers import authenticated_client, register
from ai_agent.graph_proposal.contracts import fingerprint, MANIFEST_SCHEMA


def runtime_routes():
    app = create_app()
    client = authenticated_client(app)
    owner = client.get('/api/v1/users/me').json()['id']
    graph = approved_graphs()['settings.font_size.increase']
    body = deepcopy(graph.to_dict())
    body['transitions'] = list(body['transitions'])
    edge = next(t for t in body['transitions'] if t['from_state'] == 'S1_SETTINGS_HOME')
    edge['priority'] = 0
    alternate = {**deepcopy(edge), 'transition_id': 'TEST_ALTERNATE', 'priority': 1}
    body['transitions'].append(alternate)
    graph_hash = fingerprint(body)
    now = int(time.time()) + 2
    manifest = {'schema_version': MANIFEST_SCHEMA, 'dataset_id': 'shared-routes', 'revision': '1',
                'source_revision': 'fixture', 'records': []}
    with app.state.session_factory() as db:
        db.add(ApprovedTaskGraph(id='MVP-01:route-test', graph_id='MVP-01', task_id='MVP-01',
            goal_type=graph.definition.goal_type, target_app=graph.definition.target_app,
            graph_version='route-test', graph_hash=graph_hash, schema_version='runtime-task-graph.v1',
            body=body, approved=True, active=False, priority=100))
        db.add(Consent(user_id=owner, purpose='model_training', consented=True, policy_version='1',
                       recorded_at=datetime.now(timezone.utc) - timedelta(seconds=1)))
        for i in range(4):
            session, episode = f'route-session-{i}', f'route-episode-{i}'
            db.add(AgentSession(id=session, user_id=owner, version=2, task_id='MVP-01', episode_id=episode,
                graph_hash=graph_hash, graph_version='route-test', created_at=now, updated_at=now, expires_at=now+1000))
            db.flush()
            transition = edge['transition_id'] if i < 2 else alternate['transition_id']
            first = {'schema_version':'agent-execution-event.v1', 'state_id':edge['from_state'],
                'guide_hash':fingerprint(f'guide-{i}'), 'after_observation_hash':fingerprint(f'before-{i}'),
                'target':{'node_hash':fingerprint('target'), 'role':'button', 'transition_id':transition}}
            second = {'schema_version':'agent-execution-event.v1', 'state_id':edge['from_state'],
                'previous_guide_hash': first['guide_hash'], 'guide_hash':fingerprint(f'next-{i}'),
                'before_observation_hash':first['after_observation_hash'], 'after_observation_hash':fingerprint(f'after-{i}'),
                'attempt_hash':fingerprint(f'attempt-{i}'), 'attempt_target_hash':fingerprint('target'),
                'verification_status':'NO_CHANGE' if i < 2 else 'SUCCESS', 'count_attempt':True,
                'reason_code':'verified_attempt_failed' if i < 2 else 'expected_state_observed',
                'duration_ms':2000, 'target':{'node_hash':None, 'role':'other', 'transition_id':None}}
            for seq, runtime in enumerate((first, second), 1):
                db.add(RuntimeEvent(id=f'route-{i}-{seq}', session_id=session, user_id=owner,
                    episode_id=episode, sequence=seq, request_id=f'request-{i}-{seq}', graph_hash=graph_hash,
                    kind='turn', payload={'runtime':runtime, 'device_cohort':'samsung:android-14',
                        'graph_reference':{'task_id':'MVP-01', 'version':'route-test', 'sha256':graph_hash}},
                    created_at=now, expires_at=now+100))
            manifest['records'].append({'episode_id':episode, 'group_id':session, 'split':'train',
                'source_kind':'runtime', 'review_status':'pending', 'reviewer':None, 'review_revision':None,
                'evidence_refs':[f'route-{i}-1',f'route-{i}-2'], 'label':'unknown', 'label_method':'weak'})
        db.commit()
    return app, client, owner, now, manifest


def test_database_routes_propose_without_changing_active_graph():
    app, client, owner, now, manifest = runtime_routes()
    result = propose_routes(app.state.session_factory, manifest=manifest, now=now)
    assert result['episodes'] == 4 and result['status'] == 'NEEDS_DATA'
    assert len(result['proposals']) == 1
    proposal = stored_proposal(app.state.session_factory, result['run_id'], result['proposals'][0]['proposal_id'], now=now)
    assert proposal.change['priorities']['TEST_ALTERNATE'] == 0
    assert proposal.evidence['device_cohorts'] == ['samsung:android-14']
    with app.state.session_factory() as db:
        receipt = db.get(RuntimeEventConsumption, result['run_id'])
        assert receipt.consumer == ROUTE_CONSUMER
        assert owner not in str(receipt.result)
        assert db.get(ApprovedTaskGraph, 'MVP-01:1.0.1').active
        assert not db.get(ApprovedTaskGraph, 'MVP-01:route-test').active
        assert receipt.result['analysis']['clusters'][0]['device_cohort'] == 'samsung:android-14'
    assert cleanup(app.state.session_factory, now=now+100) == 8
    with pytest.raises(ValueError, match='expired or was withdrawn'):
        stored_proposal(app.state.session_factory, result['run_id'], proposal.proposal_id, now=now+100)


def test_withdrawal_invalidates_cross_user_run_even_when_anchor_has_other_owner():
    app, client, owner, now, manifest = runtime_routes()
    other = register(client, '01077778888')['user_id']
    with app.state.session_factory() as db:
        for session in db.scalars(select(AgentSession).where(AgentSession.id.in_(['route-session-2','route-session-3']))):
            session.user_id = other
        for event in db.scalars(select(RuntimeEvent).where(RuntimeEvent.session_id.in_(['route-session-2','route-session-3']))):
            event.user_id = other
        db.add(Consent(user_id=other,purpose='model_training',consented=True,policy_version='1',
                       recorded_at=datetime.now(timezone.utc)-timedelta(seconds=1)))
        db.commit()
    result = propose_routes(app.state.session_factory, manifest=manifest, now=now)
    # Current client owns 'other'; withdrawal must erase a shared run regardless of anchor.
    assert client.post(f'/api/v1/users/{other}/consents', json={'purpose':'model_training',
        'consented':False,'policy_version':'1'}).status_code == 201
    with app.state.session_factory() as db:
        assert db.get(RuntimeEventConsumption, result['run_id']) is None
    with pytest.raises(ValueError, match='ineligible'):
        propose_routes(app.state.session_factory, manifest=manifest, now=now)


def test_operational_cohort_rejects_demographics_and_arbitrary_text():
    assert device_cohort({'manufacturer':'Samsung','os_version':'14','age_group':'60_plus'}) == 'samsung:android-14'
    assert device_cohort({'manufacturer':'private@example.com','os_version':'secret'}) == 'unknown:android-unknown'


@pytest.mark.parametrize('setting', ['font_size', 'brightness', 'volume'])
@pytest.mark.parametrize('problem', [None, 'moved', 'different_node', 'different_window', 'range_changed', 'setting_unchanged'])
def test_actual_runtime_events_link_verified_slider_action_to_original_guide(setting, problem):
    from dataclasses import replace
    from tests.test_screen_jev import scene, sender_for
    from app.services.screen_jev import JevScreenResolver
    from ai_agent.runtime import ClickAgentEngine, UserActionEvent, UserActionType
    from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext
    from ai_agent.telemetry.execution_events import produce
    from ai_agent.graph_proposal.reconstruction.episodes import reconstruct
    from ai_agent.graph_proposal.contracts import EVENT_SCHEMA
    import os
    from unittest.mock import patch
    graph, transition, node, goal = scene(setting)
    def applied(value):
        if setting == 'font_size':
            return {'source':'android.provider.Settings.System.FONT_SCALE','font_scale':1.0 + (value-3)/10}
        if setting == 'brightness':
            return {'brightness_source':'android.provider.Settings.System.SCREEN_BRIGHTNESS','screen_brightness':value}
        return {'media_volume_source':'android.media.AudioManager.STREAM_MUSIC','media_volume':value}
    with patch.dict(os.environ, {'TYPESAFE_API_KEY':'test-only-key','JEV_MODEL':'jev-1.14.0'}):
        engine = ClickAgentEngine(task_graphs={goal.task_id:graph}, screen_resolver=JevScreenResolver(sender_for(transition)))
        observed = AccessibilityNode(node_id=node.node_id, role='slider', class_name='android.widget.SeekBar',
            package_name=node.package_name, bounds_in_screen=node.bounds, extras={'value':3,'min':0,'max':10})
        request = AgentTurnRequest(request_id='r1',session_id='s',raw_goal=None,typed_goal=goal,
            device_context=DeviceContext(platform='android'),accessibility_tree=(observed,),
            captured_at_ms=1000,observation_id='o1',user_confirmation='accepted',
            applied_settings=applied(3))
        first = engine.handle_turn(request)
        before = engine.session_store.get('s')
        first_event = produce(first, None, before, 'slider')
        changed = replace(observed, extras={'value':4,'min':0,'max':11 if problem == 'range_changed' else 10})
        if problem == 'moved': changed = replace(changed, bounds_in_screen=(20,200,300,240))
        if problem == 'different_node': changed = replace(changed, node_id='other')
        if problem == 'different_window': changed = replace(changed, window_id=99)
        second = engine.handle_turn(replace(request, request_id='r2',captured_at_ms=1800,observation_id='o2',
            accessibility_tree=(changed,), applied_settings=applied(3 if problem == 'setting_unchanged' else 4),
            user_action_event=UserActionEvent(UserActionType.DRAG_COMPLETED, first.guide_command.guide_command_id,
                observed.node_id, occurred_at_ms=1100,event_id='e1')))
        second_event = produce(second, before, engine.session_store.get('s'), 'slider')
    base = {'schema_version':EVENT_SCHEMA,'session_key':'session','episode_id':'episode',
        'created_at':100,'expires_at':1000,'task_id':goal.task_id,'graph_hash':fingerprint(graph.to_dict()),
        'graph_version':'test','source_kind':'synthetic','control':{}}
    result = reconstruct([{**base,'event_id':'e1','sequence':1,'runtime':first_event},
                          {**base,'event_id':'e2','sequence':2,'runtime':second_event}],now=200)
    assert result[0]['complete_evidence']
    if problem is None:
        assert result[0]['steps'][-1]['label'] == 'verified_success'
        assert result[0]['steps'][-1]['duration_ms'] == 800
    else:
        assert result[0]['steps'][-1]['label'] != 'verified_success'
        assert not second_event['task_completed']


@pytest.mark.parametrize('problem', [None, 'withdrawn', 'expired', 'changed_patch'])
def test_runtime_release_rechecks_retained_evidence_at_publication(problem):
    from tests.test_agent2_operations import started_with_consent, release_fixture
    app, client, owner, body = started_with_consent()
    manager, release, _ = release_fixture(app)
    with app.state.session_factory() as db:
        event = db.scalar(select(RuntimeEvent))
        priorities = {t['transition_id']:t['priority'] for t in release['body']['transitions']
                      if t['from_state'] == 'S1_SETTINGS_HOME'}
        stored = {'proposal_id':release['proposal_id'], 'task_id':release['task_id'],
            'proposal_type':'transition_priority', 'change':{'priorities':priorities},
            'evidence':{'base_graph_hash':release['base_hash']}}
        receipt = RuntimeEventConsumption(event_id=event.id, consumer=ROUTE_CONSUMER,
            consumed_at=int(time.time()), result={'expires_at':int(time.time())+1000,'proposals':[stored]})
        db.add(receipt)
        db.flush()
        release['runtime_run_id'] = receipt.id
        if problem == 'expired': receipt.result = {**receipt.result,'expires_at':1}
        if problem == 'changed_patch':
            stored = deepcopy(stored)
            stored['change']['priorities'] = {key:value+1 for key,value in priorities.items()}
            receipt.result = {**receipt.result,'proposals':[stored]}
        db.commit()
    if problem == 'withdrawn':
        assert client.post(f'/api/v1/users/{owner}/consents',json={'purpose':'model_training',
            'consented':False,'policy_version':'1'}).status_code == 201
    if problem is None:
        assert manager.promote(release)['version'] == '2.0.0'
    else:
        with pytest.raises(ValueError, match='expired or was withdrawn|content mismatch'):
            manager.promote(release)
        with app.state.session_factory() as db:
            assert db.get(ApprovedTaskGraph,'MVP-01:1.0.1').active
