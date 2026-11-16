"""Finite settings execution contracts shared by online and offline agents."""
import json
from pathlib import Path

DOCUMENT = json.loads((Path(__file__).resolve().parents[3] / 'goal_catalog'
    / 'settings_execution.json').read_text(encoding='utf-8'))
CONTROLS = DOCUMENT['toggles']

# Switches and finite mode choices are different controls. Existing dark-mode
# cards retain their verifier IDs; additional modes share the same contracts.
CHOICES = dict(DOCUMENT.get('mode_choices', {}))
if CONTROLS.get('dark_mode', {}).get('choices'):
    dark = CONTROLS['dark_mode']
    CHOICES['dark_mode'] = {
        'pages': dark['pages'], 'categories': dark['categories'],
        'options': {state: {**option, 'value': 2 if state == 'on' else 1}
                    for state, option in dark['choices'].items()},
        'state_options': {'on': 'on', 'off': 'off'},
        'system': {'key': 'night_mode', 'source': 'android.app.UiModeManager.getNightMode',
                   'predicate_prefix': 'system_night_mode'},
    }


def choice_spec(slots):
    contract = CHOICES.get(slots.get('setting'))
    option = contract and contract['state_options'].get(slots.get('state'))
    return contract['options'].get(option) if option else None


def choice_predicate(slots):
    contract = CHOICES.get(slots.get('setting'))
    return contract['system']['predicate_prefix'] + '_' + slots['state'] if choice_spec(slots) else None


CHOICE_PREDICATES = {contract['system']['predicate_prefix'] + '_' + state
    for contract in CHOICES.values() for state in contract['state_options']}



BASE_PREDICATES = {'state_reached', 'target_value_increased', 'target_value_decreased', 'settings_page_visible'}


def supported_predicates(slots, task_type):
    """One finite capability contract for execution, proposals and rollout audits."""
    result = set(BASE_PREDICATES)
    if task_type.startswith('commerce_'):
        from ai_agent.task_graph.commerce import PREDICATES
        return {'state_reached', *PREDICATES}
    if slots.get('setting') in {*CONTROLS, 'wifi'}:
        result |= {'target_checked_on', 'target_checked_off'}
    if choice_spec(slots):
        result.add(choice_predicate(slots))
    if task_type == 'settings_assisted':
        result.add('manual_result_required')
    return result
