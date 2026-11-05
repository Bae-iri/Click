"""Finite settings-screen export policy, shared with the Android network boundary.

Unknown strings are removed in full. Neither regex redaction nor model inference
can grant an exception to this vocabulary.
"""
from dataclasses import replace
from functools import lru_cache
import json
from pathlib import Path

from ai_agent.runtime.keywords import normalize_text
from ai_agent.runtime.privacy import safe_extras, sensitive_node
from ai_agent.runtime.vision_vocabulary import labels_for

POLICY = json.loads((Path(__file__).resolve().parents[3] / "goal_catalog"
                     / "screen_privacy_policy.json").read_text(encoding="utf-8"))
PACKAGES = frozenset(POLICY["settings_packages"] + POLICY["launcher_packages"])
MANUAL_MESSAGE = "개인정보가 있거나 지원하지 않는 화면이라 화면 전송을 멈췄어요. 이 단계는 직접 진행해 주세요."


def label(value, setting):
    if not isinstance(value, str):
        return None
    text = " ".join(value.strip().lower().split())
    return text if text in labels_for(setting) else None


def description(node):
    resource = (node.resource_id or "").lower().rsplit("/", 1)[-1]
    return (resource in POLICY["description_resources"]
            or any(token in resource for token in POLICY["private_resource_tokens"]))


def screen_status(nodes, setting, package=None):
    if not labels_for(setting):
        return "unsupported_screen"
    if package is not None and package not in PACKAGES:
        return "unsupported_screen"
    by_id = {n.node_id: n for n in nodes}
    recognized = False
    for node in nodes:
        if not node.visible_to_user:
            continue
        if node.package_name not in PACKAGES:
            return "unsupported_screen"
        is_input = sensitive_node(node) or node.role in {"text_field", "text_input"}
        empty_search = (node.resource_id in POLICY["empty_search_resources"]
                        and not node.text and not node.extras.get("input_content_present")
                        and not any(node.extras.get(key) for key in ("password", "is_password", "isPassword")))
        if is_input and not empty_search:
            return "sensitive_screen"
        # A menu row pointing at accounts is not an account page. Only independent
        # titles (outside a control/list) can identify a sensitive page here.
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
        if independent and any(" ".join((v or "").strip().lower().split()) in POLICY["blocked_titles"]
                               for v in (node.text, node.content_description)):
            return "sensitive_screen"
        if not description(node) and not is_input:
            recognized |= bool(label(node.text, setting) or label(node.content_description, setting))
    # An empty native tree may still be handled by the consented, on-device OCR
    # path. It supplies no text to an external provider by itself.
    return None if recognized or not nodes else "unsupported_screen"


def filter_nodes(nodes, setting):
    result = []
    for node in nodes:
        hidden = sensitive_node(node) or node.role in {"text_field", "text_input"} or description(node)
        result.append(replace(node,
            text=None if hidden else label(node.text, setting),
            content_description=None if hidden else label(node.content_description, setting),
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
        if not text and node.role != "slider" and node.structure_role != "container":
            continue
        if node.role not in POLICY["roles"]:
            continue
        output.append(replace(node, label=text, label_parts=parts,
            structure_role=node.structure_role if node.structure_role in POLICY["structure_roles"] else "unknown"))
    return tuple(output)


def finite_number(value):
    return safe_extras({"value": value}).get("value")
