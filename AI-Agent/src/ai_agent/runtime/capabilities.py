"""Read-only shared capability identity. Native values/consent remain separate."""
from hashlib import sha256
import json
from functools import lru_cache
from pathlib import Path

PATH=Path(__file__).resolve().parents[3]/'goal_catalog/settings_capabilities.json'

@lru_cache(maxsize=1)
def document():
    value=json.loads(PATH.read_text())
    if value['schema_version']!='settings-capabilities.v1':raise ValueError('Unsupported settings capabilities')
    return value

def identity():
    return {'schema_version':'settings-capabilities.v1','sha256':sha256(PATH.read_bytes()).hexdigest()}

def navigation_state(graph,state_id):
    """Only graph-owned low-risk navigation; never a final value change."""
    contract=document()
    if contract['state_aliases'].get(state_id,state_id) not in contract['navigation_states']:return False
    return any(t.from_state==state_id and t.action_type.value=='tap'
        and t.safety_level.value=='LOW_RISK' and not t.completes_task and not t.confirmation_message
        and t.target_role=='menu_item' for t in graph.transitions)
