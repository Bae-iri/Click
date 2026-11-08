"""Finite settings-screen export policy, shared with the Android network boundary.

Unknown strings are removed in full. Neither regex redaction nor model inference
can grant an exception to this vocabulary.
"""
from dataclasses import dataclass, replace
from functools import lru_cache
import json
from pathlib import Path

from ai_agent.runtime.keywords import normalize_text
from ai_agent.runtime.privacy import safe_extras, sensitive_node
from ai_agent.runtime.vision_vocabulary import labels_for

POLICY = json.loads((Path(__file__).resolve().parents[3] / "goal_catalog"
                     / "screen_privacy_policy.json").read_text(encoding="utf-8"))
PACKAGES = frozenset(POLICY["settings_packages"] + POLICY["launcher_packages"])
MANUAL_MESSAGE = "현재 화면에서 안내할 항목을 확인하지 못했어요. 화면을 다시 읽거나 이 단계는 직접 진행해 주세요."
OBSERVATION_REASONS = {'unsupported_scope', 'unsupported_app', 'input_present', 'credential_input',
    'protected_screen', 'unrecognized_settings_screen', 'safe_native_labels', 'empty_observation'}


@dataclass(frozen=True)
class ScreenAssessment:
    status: str | None
    reason: str
    redacted_nodes: int = 0

    @property
    def message(self):
        if self.reason == 'unsupported_app':
            return '현재 앱은 화면 안내 대상이 아니에요. 설정 화면에서 다시 시도하거나 이 단계는 직접 진행해 주세요.'
        if self.reason in {'input_present', 'credential_input'}:
            return '입력 중인 내용을 보호하기 위해 화면 전송을 멈췄어요. 이 단계는 직접 진행해 주세요.'
        if self.reason == 'protected_screen':
            return '계정·인증 등 보호가 필요한 화면이에요. 이 단계는 직접 진행해 주세요.'
        return MANUAL_MESSAGE



def observation_contract():
    """Public policy identity only; never screen contents or configuration secrets."""
    from hashlib import sha256
    root = Path(__file__).resolve().parents[3] / 'goal_catalog'
    return {'schema_version': 'agent-observation.v1',
        'policy_sha256': sha256((root / 'screen_privacy_policy.json').read_bytes()).hexdigest(),
        'vocabulary_sha256': sha256((root / 'vision_vocabulary.json').read_bytes()).hexdigest()}


def label(value, setting):
    if not isinstance(value, str):
        return None
    text = " ".join(value.strip().lower().split())
    return text if text in labels_for(setting) else None


def description(node):
    resource = (node.resource_id or "").lower().rsplit("/", 1)[-1]
    return (resource in POLICY["description_resources"]
            or any(token in resource for token in POLICY["private_resource_tokens"]))


def private_node_ids(nodes):
    """Remove private identity rows before they can become labels or page titles."""
    by_id = {n.node_id: n for n in nodes}
    roots = set()
    tokens = (*POLICY['private_resource_tokens'], *POLICY.get('private_region_tokens', ()))
    for node in nodes:
        name = (node.resource_id or '').lower().rsplit('/', 1)[-1]
        if not any(token in name for token in tokens):
            continue
        roots.add(node.node_id)
        parent, seen = by_id.get(node.parent_id), {node.node_id}
        while parent and parent.node_id not in seen:
            seen.add(parent.node_id)
            if parent.scrollable:
                break
            if parent.clickable:
                if parent.parent_id:
                    roots.add(parent.node_id)
                break
            parent = by_id.get(parent.parent_id)
    hidden = set()
    for node in nodes:
        current, seen = node, set()
        while current and current.node_id not in seen:
            seen.add(current.node_id)
            if current.node_id in roots:
                hidden.add(node.node_id)
                break
            current = by_id.get(current.parent_id)
    return hidden


def assess_screen(nodes, setting, package=None):
    hidden = private_node_ids(nodes)
    def result(status, reason):
        return ScreenAssessment(status, reason, len(hidden))
    if not labels_for(setting):
        return result('unsupported_screen', 'unsupported_scope')
    if package is not None and package not in PACKAGES:
        return result('unsupported_screen', 'unsupported_app')
    by_id = {n.node_id: n for n in nodes}
    recognized = False
    for node in nodes:
        if not node.visible_to_user:
            continue
        if node.package_name not in PACKAGES:
            return result('unsupported_screen', 'unsupported_app')
        is_input = sensitive_node(node) or node.role in {'text_field', 'text_input'}
        password = any(node.extras.get(k) for k in ('password', 'is_password', 'isPassword'))
        empty_search = (node.resource_id in POLICY['empty_search_resources']
            and not node.text and not node.extras.get('input_content_present') and not password)
        if is_input and not empty_search:
            return result('sensitive_screen', 'credential_input' if password else 'input_present')
        if node.node_id in hidden:
            continue
        parent, seen, independent = node.parent_id, {node.node_id}, not node.clickable
        while parent and parent not in seen:
            seen.add(parent)
            ancestor = by_id.get(parent)
            if ancestor is None:
                break
            if ancestor.clickable or ancestor.scrollable:
                independent = False
                break
            parent = ancestor.parent_id
        if not description(node) and independent and any(
                ' '.join((v or '').strip().lower().split()) in POLICY['blocked_titles']
                for v in (node.text, node.content_description)):
            return result('sensitive_screen', 'protected_screen')
        if not description(node) and not is_input:
            recognized |= bool(label(node.text, setting) or label(node.content_description, setting))
    if recognized:
        return result(None, 'safe_native_labels')
    if not nodes:
        return result(None, 'empty_observation')
    return result('unsupported_screen', 'unrecognized_settings_screen')


def screen_status(nodes, setting, package=None):
    return assess_screen(nodes, setting, package).status


def filter_nodes(nodes, setting, *, finite_labels=True):
    hidden_ids = private_node_ids(nodes)
    result = []
    for node in nodes:
        hidden = (node.node_id in hidden_ids or sensitive_node(node)
            or node.role in {'text_field', 'text_input'} or description(node))
        result.append(replace(node,
            text=None if hidden else label(node.text, setting) if finite_labels else node.text,
            content_description=None if hidden else label(node.content_description, setting) if finite_labels else node.content_description,
            extras={} if hidden else safe_extras(node.extras),
            clickable=False if hidden else node.clickable,
            enabled=False if hidden else node.enabled))
    return tuple(result)


@lru_cache(maxsize=64)
def normalized_labels(setting):
    return frozenset(normalize_text(word) for word in labels_for(setting))


def minimize_nodes(nodes, setting):
    allowed = normalized_labels(setting)
    output = []
    for node in nodes:
        if node.package_name not in PACKAGES or node.structure_role == "description" or description(node):
            continue
        parts = tuple(dict.fromkeys(part for part in node.label_parts if part in allowed))
        text = node.label if node.label in allowed else " ".join(parts)
        # An unlabeled numeric slider may be identified using an approved page
        # title and hierarchy. Its unknown description is never exported.
        native_checked = (node.role in {'toggle', 'radio'} and node.checkable
            and type(node.checked) is bool and node.source_confidence is None
            and not node.node_id.startswith('vision:'))
        if not text and node.role != "slider" and node.structure_role != "container" and not native_checked:
            continue
        if node.role not in POLICY["roles"]:
            continue
        output.append(replace(node, label=text, label_parts=parts,
            structure_role=node.structure_role if node.structure_role in POLICY["structure_roles"] else "unknown"))
    return tuple(output)


def finite_number(value):
    return safe_extras({"value": value}).get("value")
