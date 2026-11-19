"""Read-only retained Government24 graphs, fenced by consent and retention."""
from datetime import timezone
import time
from sqlalchemy import and_, cast, Float, func, select
from ai_agent.task_graph.fingerprints import fingerprint
from ai_agent.task_graph.gov24 import VERSION, validate_graph
from app.models.agent_session import AgentSession
from app.models.graph_proposal import GOV24_GRAPH_CONSUMER, GraphProposal
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.models.user import Consent
from app.repositories.users import UserRepository
from app.services.public_guide_policy import CIVIL, CIVIL_GRAPHS, POLICY


def eligible_events(db, session_id, user_id, now):
    consent = UserRepository(db).latest_consent(user_id, 'model_training')
    if not consent or not consent.consented:
        return []
    granted = consent.recorded_at.replace(tzinfo=timezone.utc).timestamp()
    events = list(db.scalars(select(RuntimeEvent).where(RuntimeEvent.session_id == session_id,
        RuntimeEvent.user_id == user_id, RuntimeEvent.kind == 'public_guide')
        .order_by(RuntimeEvent.sequence).limit(POLICY['max_turns'] + 2)))
    if (not events or len(events) > POLICY['max_turns'] + 1
            or [e.sequence for e in events] != list(range(1, len(events) + 1))
            or any(e.created_at < granted or e.expires_at <= now for e in events)):
        return []
    return events


def retained_graphs(factory, code_hash, now):
    graphs = []
    with factory() as db:
        rows = db.execute(select(GraphProposal, RuntimeEventConsumption, RuntimeEvent)
            .join(RuntimeEventConsumption, GraphProposal.runtime_receipt_id == RuntimeEventConsumption.id)
            .join(RuntimeEvent, RuntimeEventConsumption.event_id == RuntimeEvent.id)
            .where(GraphProposal.proposal_type == 'new_gov24_task_graph', RuntimeEventConsumption.consumer == GOV24_GRAPH_CONSUMER,
                RuntimeEventConsumption.expires_at > now, RuntimeEvent.expires_at > now)
            .order_by(GraphProposal.created_at.desc(), GraphProposal.id).limit(100)).all()
        for proposal, receipt, anchor in rows:
            events = eligible_events(db, anchor.session_id, anchor.user_id, now)
            records = [dict(event_id=e.id, sequence=e.sequence, evidence=e.payload.get('public_guide', {})) for e in events]
            graph = proposal.change.get('graph')
            if (not events or proposal.evidence.get('event_ids') != [e.id for e in events]
                    or fingerprint(dict(generator=VERSION, records=records)) != receipt.result.get('input_hash')
                    or not graph or graph.get('code_hash') != code_hash
                    or fingerprint(graph) != proposal.evidence.get('graph_hash')
                    or fingerprint(graph) != receipt.result.get('graph_hash')):
                continue
            try:
                validate_graph(graph, CIVIL, POLICY['package'])
            except (ValueError, KeyError, TypeError):
                continue
            graphs.append(graph)
    return graphs


def graph_candidates(factory, code_hash, source, options, now, *, goal_type=None):
    candidates = {}
    for graph in retained_graphs(factory, code_hash, now):
        if graph['definition']['goal_type'] != (goal_type or CIVIL['goal_type']):
            continue
        for edge in graph['transitions']:
            state = graph['states'][edge['from_state']]
            if state['source_signature'] == source and edge['option_id'] in options:
                candidates.setdefault(edge['option_id'], dict(graph_hash=fingerprint(graph),
                    graph_task_id=graph['definition']['task_id']))
    return candidates


def graph_summary(factory, *, code_hash=None, now=None):
    from app.services.gov24_navigation import CODE_HASH
    now = int(time.time()) if now is None else now
    unique = {fingerprint(g): g for g in retained_graphs(factory, code_hash or CODE_HASH, now)}
    return [dict(task_id=g['definition']['task_id'], graph_hash=fingerprint(g),
        state_count=len(g['states']), transition_count=len(g['transitions']),
        goal_type=g['definition']['goal_type'], completion_scope=g['completion']['scope'])
        for g in unique.values()]
