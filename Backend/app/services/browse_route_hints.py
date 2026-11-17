"""Project AI2 observed drafts onto current candidates; hints grant no authority."""
from collections import Counter
from datetime import timezone
import json
import logging

from sqlalchemy import select, func
from app.models.graph_proposal import GraphProposal, BROWSE_GRAPH_CONSUMER
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.repositories.users import UserRepository
from app.services import browse_policy as policy

log = logging.getLogger(__name__)


class BrowseRouteHints:
    def __init__(self, factory):
        self.factory = factory

    def get(self, goal, screen, candidates, now):
        allowed = {(kind, policy.resource(node.resource_id)) for kind, node in candidates}
        page = policy.page(screen)
        counts = Counter()
        try:
            with self.factory() as db:
                rows = db.execute(select(GraphProposal, RuntimeEventConsumption, RuntimeEvent)
                    .join(RuntimeEventConsumption, GraphProposal.runtime_receipt_id == RuntimeEventConsumption.id)
                    .join(RuntimeEvent, RuntimeEventConsumption.event_id == RuntimeEvent.id)
                    .where(GraphProposal.proposal_type == 'new_browse_task_graph',
                        RuntimeEventConsumption.consumer == BROWSE_GRAPH_CONSUMER,
                        RuntimeEventConsumption.expires_at > now, RuntimeEvent.expires_at > now)
                    .order_by(GraphProposal.created_at.desc(), GraphProposal.id).limit(100)).all()
                for proposal, receipt, event in rows:
                    consent = UserRepository(db).latest_consent(event.user_id, 'model_training')
                    if not consent or not consent.consented:
                        continue
                    granted = consent.recorded_at.replace(tzinfo=timezone.utc).timestamp()
                    if event.created_at < granted:
                        continue
                    body = proposal.change.get('graph', {})
                    expected = policy.digest(json.dumps(body, sort_keys=True, ensure_ascii=False,
                        separators=(',', ':'), allow_nan=False))
                    if (receipt.result.get('status') != 'DRAFT_CREATED'
                            or receipt.result.get('proposal_id') != proposal.id
                            or expected != proposal.evidence.get('graph_hash')
                            or body.get('schema_version') != 'browse-task-graph.v1'
                            or body.get('policy_version') != policy.VERSION
                            or body.get('policy_hash') != policy.digest(json.dumps(policy.POLICY, sort_keys=True))
                            or body.get('definition', {}).get('goal_type') != goal['goal_type']
                            or proposal.evidence.get('outcome') != 'verified_navigation'):
                        continue
                    # One source trace supports each hint at most once.
                    hints = set()
                    for edge in body.get('transitions', []):
                        source = body.get('states', {}).get(edge.get('from_state'), {}).get('page')
                        dest = body.get('states', {}).get(edge.get('to_state'), {}).get('page')
                        kind, resource = edge.get('action_kind'), edge.get('target', {}).get('resource_key')
                        if (source == page and (kind, resource) in allowed
                                and dest in {'search', 'results', 'detail'}
                                and edge.get('manual_only') is True
                                and edge.get('verification') == 'correlated_public_screen_change'):
                            hints.add((kind, resource, dest))
                    counts.update(hints)
        except Exception as error:
            # Optional context must never interrupt a live request or expose SQL/user data.
            log.warning('AI2 route hints unavailable (%s)', type(error).__name__)
            return []
        observed = [{'action': k, 'resource': r, 'observed_next_page': d, 'supporting_traces': count}
            for (k, r, d), count in sorted(counts.items(), key=lambda item: (-item[1], item[0]))[:12]]
        return observed + self.repairs(goal, page, allowed, now)

    def repairs(self, goal, page, allowed, now):
        """Only the latest analysis of a session may revise advisory candidates."""
        counts = Counter()
        try:
            with self.factory() as db:
                latest = select(RuntimeEvent.session_id, func.max(RuntimeEvent.sequence).label('seq')).where(
                    RuntimeEvent.kind == 'browse').group_by(RuntimeEvent.session_id).subquery()
                rows = db.execute(select(RuntimeEventConsumption, RuntimeEvent)
                    .join(RuntimeEvent, RuntimeEventConsumption.event_id == RuntimeEvent.id)
                    .join(latest, (latest.c.session_id == RuntimeEvent.session_id) & (latest.c.seq == RuntimeEvent.sequence))
                    .where(RuntimeEventConsumption.consumer == BROWSE_GRAPH_CONSUMER,
                        RuntimeEventConsumption.expires_at > now, RuntimeEvent.expires_at > now)
                    .order_by(RuntimeEvent.created_at.desc(), RuntimeEvent.id).limit(100)).all()
                for receipt, event in rows:
                    consent = UserRepository(db).latest_consent(event.user_id, 'model_training')
                    if (not consent or not consent.consented
                            or event.created_at < consent.recorded_at.replace(tzinfo=timezone.utc).timestamp()):
                        continue
                    repair = receipt.result.get('repairs')
                    if not isinstance(repair, dict):
                        continue
                    digest = policy.digest(json.dumps(repair, sort_keys=True, ensure_ascii=False,
                        separators=(',', ':'), allow_nan=False))
                    if (digest != receipt.result.get('repairs_hash') or repair.get('schema_version') != 'browse-repairs.v1'
                            or repair.get('goal_type') != goal['goal_type']
                            or repair.get('policy_hash') != policy.digest(json.dumps(policy.POLICY, sort_keys=True))):
                        continue
                    seen = set()
                    for item in repair.get('advice', []):
                        advice = item.get('advice')
                        if item.get('page') == page and advice in {
                                'request_additional_observation', 'reobserve_before_repeating',
                                'check_jev_connection', 'check_jev_response_contract'}:
                            seen.add((advice, '', ''))
                    for item in repair.get('unconfirmed_routes', []):
                        action, resource = item.get('action'), item.get('resource')
                        if item.get('page') == page and (action, resource) in allowed:
                            seen.add(('reobserve_before_repeating', action, resource))
                    counts.update(seen)
        except Exception as error:
            log.warning('AI2 repair hints unavailable (%s)', type(error).__name__)
            return []
        return [{'advice': advice, **({'action': action, 'resource': resource} if action else {}),
            'supporting_traces': n} for (advice, action, resource), n in sorted(counts.items())][:12]
