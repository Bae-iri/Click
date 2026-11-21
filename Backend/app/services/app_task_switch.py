"""Match one requested switch to its native control and desired state."""
from dataclasses import dataclass
import json
from pathlib import Path
import re

from app.schemas.app_task import TaskCompletion, TaskResultCheck
from app.services.app_task_completion import NativeStateGoal


_RULES = json.loads((Path(__file__).resolve().parents[3] / 'AI-Agent/goal_catalog/app_completion/switch_goal.json').read_text(encoding='utf-8'))
_FORMS = [(re.compile(f['pattern'], re.I), f['target'], f['verb']) for f in _RULES['forms']]
_EXCLUDED = re.compile(_RULES['excluded_target'], re.I)


def _name(label):
    return ''.join(label.lower().split())


_GENERIC = {_name(label) for label in _RULES['generic_switch_labels']}


@dataclass(frozen=True)
class SwitchGoal:
    name: str
    checked: bool

    def bind(self, package, nodes):
        """A generic 'Use' switch needs both its page heading and a unique switch."""
        nodes = [n for n in nodes if n.source == 'accessibility']
        switches = [n for n in nodes if n.role == 'toggle']
        named = [n for n in switches if _name(n.label) == self.name]
        checks = []
        if len(named) == 1:
            switch = named[0]
        elif not named and len(switches) == 1 and _name(switches[0].label) in _GENERIC:
            titles = [n for n in nodes if n.native_heading and n.role == 'text'
                      and not (n.clickable or n.editable or n.scrollable) and _name(n.label) == self.name]
            if len(titles) != 1:
                return None
            title = titles[0]
            checks.append(TaskResultCheck(package=package, label=title.label, resource=title.resource, role='text'))
            switch = switches[0]
        else:
            return None
        if switch.checked is None:
            return None
        checks.append(TaskResultCheck(package=package, label=switch.label, resource=switch.resource,
                                      role='toggle', checked=self.checked))
        return NativeStateGoal(switch, TaskCompletion(checks=checks))


def switch_goal(goal):
    text = (goal or '').strip().rstrip('.!?').strip().lower()
    for pattern, target_group, verb_group in _FORMS:
        match = pattern.fullmatch(text)
        if match:
            target = match[target_group].strip()
            if not target or _EXCLUDED.search(target) or _name(target) in _GENERIC:
                return None
            return SwitchGoal(_name(target), match[verb_group] in _RULES['on'])
    return None
