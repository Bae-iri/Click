"""Goal-bound success evidence. Page arrival, control state, value change and user report differ.

The shared generated contract owns the success kind for every approved goal. Unknown or
assisted goals cannot obtain technical success from a generic CHANGED status.
"""
from ai_agent.runtime.capabilities import document
from math import isfinite

def contracts():
    return document()['goals']

def success_contract(goal_type):
    return contracts().get(goal_type or '', {}).get('success', {})

def numeric(value):
    return type(value) in (int, float) and isfinite(value)

def correlated_change(proof):
    identity=('attempt_id','guide_command_id','target_key','event_key','baseline_observation_id','observation_id')
    if not all(isinstance(proof.get(key),str) and proof[key] for key in identity):
        return False
    before=proof.get('baseline_captured_at_ms'); action=proof.get('action_at_ms'); after=proof.get('verified_at_ms')
    return (all(numeric(value) for value in (before,action,after)) and before<action<=after
        and proof['baseline_observation_id']!=proof['observation_id'])

def technical_success(proof, goal_type):
    spec=success_contract(goal_type)
    if not spec or not proof or proof.get('setting') != spec['setting']:
        return False
    if proof.get('goal_type') not in (None, goal_type):
        return False
    kind=spec['kind']; status=proof.get('status'); reason=proof.get('reason')
    if kind=='page_opened':
        return (status=='OPENED' and reason=='settings_page_visible'
            and isinstance(proof.get('observation_id'),str) and bool(proof['observation_id']))
    if kind=='user_report' or status not in {'CHANGED','ALREADY_SET'}:
        return False
    if status=='CHANGED' and not correlated_change(proof):
        return False
    if kind=='persisted_value':
        before=proof.get('baseline_setting_value'); after=proof.get('setting_value')
        domain = spec.get('value_domain')
        if domain and not all(numeric(v) and (not domain.get('integer') or type(v) is int)
                and domain['minimum'] <= v <= domain['maximum'] for v in (before, after)):
            return False
        expected='target_value_increased' if spec['direction']=='increase' else 'target_value_decreased'
        return bool(status=='CHANGED' and reason==expected and numeric(before) and numeric(after)
            and (after>before if spec['direction']=='increase' else after<before))
    expected=spec['requested_state']=='on'
    if proof.get('requested_state') != spec['requested_state']:
        return False
    choice = spec.get('system_choice')
    if choice and reason in {choice['reason'], 'system_mode_already_set'}:
        value = proof.get('setting_value'); baseline = proof.get('baseline_setting_value')
        wanted = choice['requested_value']
        return (type(value) is int and value == wanted
            and (status == 'ALREADY_SET' and reason == 'system_mode_already_set'
                or status == 'CHANGED' and reason == choice['reason']
                and type(baseline) is int and baseline in choice['valid_values'] and baseline != wanted))
    if kind == 'system_choice':
        return False
    expected_reason=('target_checked_on' if expected else 'target_checked_off') if status=='CHANGED' else ('wifi_already_set' if kind=='adapter_power' else 'native_switch_already_set')
    if reason!=expected_reason or proof.get('after_checked') is not expected:
        return False
    if status=='CHANGED' and proof.get('baseline_checked') is not (not expected):
        return False
    return kind!='adapter_power' or type(proof.get('setting_value')) is int and proof['setting_value']==(3 if expected else 1)

def normalize_technical(proof, goal_type):
    proof=dict(proof or {'status':'UNVERIFIABLE','reason':'insufficient_evidence'})
    if proof.get('status') in {'CHANGED','ALREADY_SET','OPENED'} and not technical_success(proof,goal_type):
        spec=success_contract(goal_type)
        return {'status':'UNVERIFIABLE','reason':'manual_result_required' if spec.get('kind')=='user_report' else 'goal_evidence_mismatch',
                'setting':spec.get('setting'),'goal_type':goal_type}
    return proof
