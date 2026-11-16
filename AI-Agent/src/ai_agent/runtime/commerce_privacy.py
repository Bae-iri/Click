"""Native commerce observation: finite UI labels, never customer or input text."""
from dataclasses import replace
from ai_agent.task_graph.commerce import DOCUMENT, PACKAGES, vocabulary
from ai_agent.runtime.privacy import safe_extras, sensitive_node
from ai_agent.runtime.screen_privacy import ScreenAssessment, POLICY

LABELS = vocabulary()
LAUNCHERS = frozenset(POLICY['launcher_packages'])
PRIVATE = tuple(POLICY['private_resource_tokens']) + tuple(DOCUMENT['private_resource_tokens']) + (
    'product_name', 'product_title', 'goods_name', 'item_name', 'search_history_item',
)


def approved(value):
    if not isinstance(value, str):
        return None
    value = ' '.join(value.strip().lower().split())
    return value if value in LABELS else None


def filter_observation(nodes, package=None):
    visible = tuple(n for n in nodes if n.visible_to_user)
    packages = {n.package_name for n in visible}
    allowed = PACKAGES | LAUNCHERS
    if package is not None and package not in allowed or packages - allowed:
        return (), ScreenAssessment('unsupported_screen', 'unsupported_app')
    if len({n.node_id for n in nodes}) != len(nodes) or len({n.window_id for n in visible}) > 1:
        return (), ScreenAssessment('unsupported_screen', 'unrecognized_settings_screen')
    by_id = {n.node_id: n for n in nodes}
    roots = {n.node_id for n in nodes if any(token in (n.resource_id or '').lower().rsplit('/', 1)[-1]
        for token in PRIVATE)}
    hidden = set()
    for node in nodes:
        current, seen = node, set()
        while current and current.node_id not in seen:
            seen.add(current.node_id)
            if current.node_id in roots:
                hidden.add(node.node_id)
                break
            current = by_id.get(current.parent_id)
    result = []
    for node in visible:
        private = (node.node_id in hidden or sensitive_node(node)
            or node.role in {'text_field', 'text_input'})
        text = None if private else approved(node.text)
        content = None if private else approved(node.content_description)
        if node.package_name in LAUNCHERS:
            text = text if text in {'쿠팡', 'coupang'} else None
            content = content if content in {'쿠팡', 'coupang'} else None
        result.append(replace(node, text=text, content_description=content,
            clickable=node.clickable and not private, enabled=node.enabled and not private,
            extras={} if private else safe_extras(node.extras)))
    return tuple(result), ScreenAssessment(None, 'safe_native_labels', len(hidden))
