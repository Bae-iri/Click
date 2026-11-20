"""Read-only AI2 Settings routes for reuse, fenced by consent, retention and compatibility.

A route becomes reusable only after independent runs reproduce the same verified path
under the same device scope, base graph, runtime code and Jev model. A route that later
fails after reuse is quarantined while that failure evidence is retained.
"""
import os
import time

from sqlalchemy import select

from ai_agent.task_graph.serialization import fingerprint
from app.models.graph_proposal import GraphProposal, SETTINGS_GRAPH_CONSUMER
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.repositories.users import UserRepository
from app.services.settings_planner import GOALS, navigation_key

COMPATIBILITY = ('scope_hash', 'base_graph_hash', 'runtime_code_hash', 'jev_model')


def reuse_enabled():
    return os.getenv('CLICK_SETTINGS_ROUTE_REUSE_ENABLED', '0') == '1'


def min_support():
    value = os.getenv('CLICK_SETTINGS_ROUTE_MIN_SUPPORT', '2').strip()
    if not value.isdigit() or not 1 <= int(value) <= 100:
        raise ValueError('CLICK_SETTINGS_ROUTE_MIN_SUPPORT must be between 1 and 100')
    return int(value)


def subgoals(route):
    return ['adjust_setting' if edge['completes_task'] else navigation_key(edge['to_state'])
            for edge in route['transitions']]


def _receipts(db, now, limit):
    rows = db.execute(select(RuntimeEventConsumption, RuntimeEvent)
        .join(RuntimeEvent, RuntimeEventConsumption.event_id == RuntimeEvent.id)
        .where(RuntimeEventConsumption.consumer == SETTINGS_GRAPH_CONSUMER,
               RuntimeEventConsumption.expires_at > now, RuntimeEvent.expires_at > now)
        .order_by(RuntimeEventConsumption.consumed_at.desc(), RuntimeEventConsumption.id).limit(limit)).all()
    users = UserRepository(db)
    for receipt, event in rows:
        consent = users.latest_consent(event.user_id, 'model_training')
        # Withdrawal or a newer grant invalidates evidence analysed under an older consent.
        if consent and consent.consented and receipt.result.get('analysis_consent_id') == consent.id:
            yield receipt


def route_inventory(factory, *, goal_type=None, now=None, limit=500):
    """Every retained route candidate with its support and reuse failures."""
    now = int(time.time()) if now is None else now
    routes, failures = {}, {}
    with factory() as db:
        for receipt in _receipts(db, now, limit):
            result = receipt.result
            for rejected in result.get('rejected_route_hashes', []):
                failures.setdefault(rejected, set()).add(result.get('run_ref') or receipt.id)
            if result.get('status') != 'DRAFT_CREATED':
                continue
            proposal = db.scalar(select(GraphProposal).where(GraphProposal.runtime_receipt_id == receipt.id,
                GraphProposal.proposal_type == 'settings_observed_route'))
            route = proposal and proposal.change.get('graph')
            if (not route or fingerprint(route) != result.get('route_hash')
                    or fingerprint(route) != proposal.evidence.get('route_hash')
                    or route['definition']['goal_type'] not in GOALS
                    or goal_type and route['definition']['goal_type'] != goal_type):
                continue
            entry = routes.setdefault(result['route_hash'], dict(route_hash=result['route_hash'],
                goal_type=route['definition']['goal_type'], start_state=route['start_state'],
                subgoals=subgoals(route), compatibility={k: route['compatibility'].get(k) for k in COMPATIBILITY},
                runs=set(), proposal_ids=[]))
            entry['runs'].add(result.get('run_ref') or receipt.id)
            entry['proposal_ids'].append(proposal.id)
    required = min_support()
    inventory = []
    for route_hash, entry in routes.items():
        failed = failures.get(route_hash, set())
        support = len(entry.pop('runs'))
        inventory.append(dict(entry, support=support, reuse_failures=len(failed),
            status='QUARANTINED' if failed else 'VALIDATED' if support >= required else 'CANDIDATE'))
    return sorted(inventory, key=lambda r: (r['goal_type'], -r['support'], r['route_hash']))


def reusable_routes(factory, goal_type, *, now=None):
    """Serializable routes handed to the database-free runtime process."""
    if goal_type not in GOALS:
        return []
    return [{k: r[k] for k in ('route_hash', 'goal_type', 'start_state', 'subgoals', 'compatibility', 'support')}
            for r in route_inventory(factory, goal_type=goal_type, now=now) if r['status'] == 'VALIDATED']
