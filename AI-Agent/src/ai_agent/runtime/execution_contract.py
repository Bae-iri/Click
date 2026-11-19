"""Shared identity and lifecycle contract, used before runtime decisions and at HTTP boundaries."""
import json
from functools import lru_cache
from pathlib import Path

@lru_cache(maxsize=1)
def policy():
    return json.loads((Path(__file__).resolve().parents[3]/'goal_catalog/execution_state_contract.json').read_text(encoding="utf-8"))

def validate_context(context, *, session_id, request_id, episode_id, observation_id, version=None):
    if context is None:
        return  # Compatibility for explicitly unnegotiated older callers.
    expected={'protocol_version':policy()['version'],'session_id':session_id,'request_id':request_id,
              'episode_id':episode_id,'observation_id':observation_id}
    if any(context.get(k)!=v for k,v in expected.items()):
        raise ValueError('EXECUTION_CONTEXT_MISMATCH')
    actual=context.get('session_version')
    if type(actual) is not int or actual<0 or (version is not None and actual!=version):
        raise ValueError('EXECUTION_VERSION_MISMATCH')

def project_phase(status,command='snapshot',source='snapshot',guide=None,verification=None):
    spec=policy();phase=spec['status_phases'][status]
    if command in spec['command_phases']:return spec['command_phases'][command]
    if source=='snapshot' or status!='IN_PROGRESS':return phase
    if (verification or {}).get('attempt_id') and (verification or {}).get('status') in spec['verification_wait_statuses']:
        return spec['verification_phase']
    if ((guide or {}).get('render_policy') or {}).get('manual_handoff'):return spec['manual_phase']
    if (guide or {}).get('requires_user_action') and (guide or {}).get('action_type') in {'tap','drag'}:
        return spec['guidance_phase']
    return phase

def state(*, session_id, episode_id, version, status, request_id=None, command='snapshot',
          observation_id=None, attempt_id=None, guide_id=None, verification=None, source='snapshot',graph_hash=None,guide=None,goal_type=None):
    phases=policy()['status_phases']
    if status not in phases or type(version) is not int or version<0:
        raise ValueError('EXECUTION_STATE_INVALID')
    return dict(protocol_version=policy()['version'],session_id=session_id,episode_id=episode_id,
        session_version=version,request_id=request_id,command_type=command,task_status=status,
        phase=project_phase(status,command,source,guide,verification),observation_id=observation_id,attempt_id=attempt_id,
        guide_command_id=guide_id,graph_hash=graph_hash,goal_type=goal_type,verification_attempt_id=(verification or {}).get('attempt_id'),
        verification_status=(verification or {}).get('status'),source=source)
