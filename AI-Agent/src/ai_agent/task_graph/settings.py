"""Finite settings execution contracts shared by online and offline agents."""
import json
from pathlib import Path

DOCUMENT = json.loads((Path(__file__).resolve().parents[3] / 'goal_catalog'
    / 'settings_execution.json').read_text(encoding='utf-8'))
CONTROLS = DOCUMENT['toggles']


BASE_PREDICATES = {'state_reached', 'target_value_increased', 'target_value_decreased', 'settings_page_visible'}


def supported_predicates(slots, task_type):
    """One finite capability contract for execution, proposals and rollout audits."""
    result = set(BASE_PREDICATES)
    if slots.get('setting') in {*CONTROLS, 'wifi'}:
        result |= {'target_checked_on', 'target_checked_off'}
    if slots.get('setting') == 'dark_mode' and CONTROLS['dark_mode'].get('choices'):
        result |= {'system_night_mode_on', 'system_night_mode_off'}
    if task_type == 'settings_assisted':
        result.add('manual_result_required')
    return result
