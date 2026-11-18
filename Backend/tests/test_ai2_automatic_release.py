"""Synthetic release fixtures verify automation, not actual device success."""
from copy import deepcopy
from dataclasses import asdict
import time
import pytest
from sqlalchemy import select
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.models.graph_release import GraphReleaseAudit
from app.models.task_graph import ApprovedTaskGraph
from app.repositories.task_graphs import TaskGraphRepository, TASK_GRAPHS, canonical_graph
from app.repositories.graph_releases import GraphReleaseRepository
from ai_agent.graph_proposal.contracts import fingerprint, MANIFEST_SCHEMA
from ai_agent.graph_proposal.schemas import GraphUpdateProposal
from scripts.agent2 import auto_select_routes, ROUTE_CONSUMER
from tests.test_agent2_operations import started_with_consent


def setup_run():
    app, _, _, _ = started_with_consent()
    factory = app.state.session_factory
    base, _ = canonical_graph(TASK_GRAPHS['settings.font_size.increase'])
    base['definition']['task_id'] = 'AI2-AUTO-TEST'
    first = base['transitions'][0]
    first['priority'] = 0
    state, original = first['from_state'], first['transition_id']
    base['transitions'] += [{**deepcopy(first), 'transition_id': key, 'priority': i}
                            for i, key in enumerate(('ALT-B', 'ALT-C'), 1)]
    TaskGraphRepository(factory).register(base, version='1.0.0', approved=True)
    metrics = {original: {'attempts': 10, 'success_rate': .2},
               'ALT-B': {'attempts': 10, 'success_rate': .8},
               'ALT-C': {'attempts': 10, 'success_rate': .9}}
    proposals = []
    for best in ('ALT-B', 'ALT-C'):
        order = [best] + [key for key in metrics if key != best]
        proposals.append(GraphUpdateProposal('proposal-' + best, 'AI2-AUTO-TEST', '1.0.0',
            'transition_priority', {'priorities': {key: i for i, key in enumerate(order)}},
            {'base_graph_hash': fingerprint(base), 'state_id': state, 'route_metrics': metrics}, .9, False))
    now = int(time.time())
    with factory() as db:
        anchor = db.scalar(select(RuntimeEvent))
        receipt = RuntimeEventConsumption(event_id=anchor.id, consumer=ROUTE_CONSUMER,
            result={'expires_at': now + 100, 'proposals': [asdict(p) for p in proposals]}, consumed_at=now)
        db.add(receipt)
        db.commit()
        run_id = receipt.id
    manifest = {'schema_version': MANIFEST_SCHEMA, 'dataset_id': 'auto-test', 'revision': '1',
        'source_revision': 'fixture', 'records': [{'episode_id': 'holdout', 'group_id': 'holdout',
        'split': 'holdout', 'source_kind': 'device_test', 'review_status': 'independent_review',
        'reviewer': 'fixture-evaluator', 'review_revision': '1', 'evidence_refs': ['fixture-ref'],
        'label': 'unknown', 'label_method': 'independent_evidence'}]}
    cases = [{'episode_id': 'holdout', 'source_kind': 'device_test', 'graph_hash': fingerprint(base),
              'task_id': 'AI2-AUTO-TEST', 'review_revision': '1', 'evidence_refs': ['fixture-ref']}]
    return factory, run_id, base, manifest, cases


def test_agent2_picks_best_passing_candidate_and_audits_without_human_approval():
    factory, run_id, base, manifest, cases = setup_run()
    result = auto_select_routes(factory, run_id, manifest=manifest, cases=cases,
        evaluator=lambda body, case: {'checks': 1, 'failures': []})
    assert result['status'] == 'PUBLISHED' and len(result['published']) == 1
    assert result['published'][0]['selection']['key'] == 'proposal-ALT-C'
    with factory() as db:
        old = db.get(ApprovedTaskGraph, 'AI2-AUTO-TEST:1.0.0')
        assert not old.active and old.approved
        audit = db.scalar(select(GraphReleaseAudit).where(GraphReleaseAudit.action == 'promote'))
        assert audit.reviewer == 'ai2-success-selection.v1'
        assert audit.details['selection']['success_rate'] == .9
        active = db.get(ApprovedTaskGraph, audit.release_id)
        assert active.active and active.approved
    # Existing sessions keep their exact immutable base after auto promotion.
    _, ref = TaskGraphRepository(factory).select_pinned({'task_id': 'AI2-AUTO-TEST',
        'graph_version': '1.0.0', 'goal_type': base['definition']['goal_type'],
        'goal': {'target_app': base['definition']['target_app']}, 'graph_hash': fingerprint(base)})
    assert ref['sha256'] == fingerprint(base)


@pytest.mark.parametrize('failure', ['missing_cases', 'regression', 'expired'])
def test_automatic_release_does_not_publish_missing_failed_or_expired_evidence(failure):
    factory, run_id, _, manifest, cases = setup_run()
    if failure == 'expired':
        with pytest.raises(ValueError, match='expired'):
            auto_select_routes(factory, run_id, manifest=manifest, cases=cases, now=int(time.time())+200)
    else:
        result = auto_select_routes(factory, run_id, manifest=manifest,
            cases=[] if failure == 'missing_cases' else cases,
            evaluator=lambda body, case: {'checks': 1, 'failures': ['wrong target']})
        assert result['status'] == 'NEEDS_DATA'
    with factory() as db:
        assert db.get(ApprovedTaskGraph, 'AI2-AUTO-TEST:1.0.0').active
        assert db.scalar(select(GraphReleaseAudit)) is None
