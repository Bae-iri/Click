"""Trusted executable pairs and untrusted recovery evidence are separate inputs."""
from dataclasses import dataclass
import re


NODE_TOOLS = frozenset(('tap', 'set_text', 'scroll_forward', 'scroll_backward'))
ACTION_TOOLS = NODE_TOOLS | {'back', 'open_app'}


def action_pair(tool, target_id=None, package=''):
    return tool, package if tool == 'open_app' else '' if tool == 'back' else target_id


@dataclass(frozen=True)
class ActionCapabilities:
    """Server-only authority ceiling; targets are native IDs, selected apps, or '' for back."""
    pairs: frozenset[tuple[str, str]]

    def __post_init__(self):
        pairs = frozenset(self.pairs)
        for pair in pairs:
            if not isinstance(pair, tuple) or len(pair) != 2:
                raise ValueError('Invalid trusted action capability')
            tool, target = pair
            if tool not in ACTION_TOOLS or not isinstance(target, str) or len(target) > 200:
                raise ValueError('Invalid trusted action capability')
            if tool == 'back' and target != '' or tool != 'back' and not target:
                raise ValueError('Invalid trusted action target')
            if tool == 'open_app' and not re.fullmatch(r'[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+', target):
                raise ValueError('Invalid trusted app capability')
        object.__setattr__(self, 'pairs', pairs)

    def allows_action(self, action):
        return action_pair(action.tool, action.target_id, action.package) in self.pairs


def native_action_pairs(request, nodes):
    pairs = {('back', '')} | {('open_app', app.package) for app in request.apps}
    for node in nodes:
        if node.clickable and not node.editable:pairs.add(('tap', node.id))
        if node.editable:pairs.add(('set_text', node.id))
        if node.scrollable:
            pairs.update((tool, node.id) for tool in ('scroll_forward', 'scroll_backward'))
    return frozenset(pairs)


def recovery_applies(request, reference):
    if reference.fingerprint != request.screen.fingerprint:return False
    if reference.tool == 'open_app':
        return reference.package in {app.package for app in request.apps}
    return reference.package == request.screen.package


def recovery_blocked_pairs(request):
    if request.recovery is None:return frozenset()
    return frozenset(action_pair(ref.tool, ref.target_id, ref.package)
        for ref in request.recovery.blocked_actions if recovery_applies(request, ref))


def _aliased_reference(request, reference, inverse):
    current = recovery_applies(request, reference)
    target = ('none' if reference.tool in {'back', 'open_app'} else
        inverse.get(reference.target_id, 'not_visible')) if current else 'not_current'
    # A historical package/ID is not disclosed or converted into a new target.
    return dict(tool=reference.tool, target=target,
        app=reference.package if current and reference.tool == 'open_app' else 'none', same_screen=current)


def recovery_context(request, indexed):
    recovery = request.recovery
    if recovery is None:return None
    inverse = {node.id: alias for alias, node in indexed.items()}
    return dict(mode=recovery.policy, reason=recovery.reason, last_verdict=recovery.last_verdict,
        waited_ms=recovery.waited_ms, attempt=recovery.attempt,
        last_action=_aliased_reference(request, recovery.last_action, inverse),
        blocked_actions=[_aliased_reference(request, ref, inverse) for ref in recovery.blocked_actions])


def aliased_actions(pairs, indexed):
    inverse = {node.id: alias for alias, node in indexed.items()}
    return [dict(tool=tool, target=inverse[target] if tool in NODE_TOOLS else 'none',
        app=target if tool == 'open_app' else 'none') for tool, target in sorted(pairs)]
