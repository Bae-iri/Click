"""Conservative text redaction and input-field removal before inference."""
from dataclasses import replace
import math
import re
from ai_agent.runtime.vision_vocabulary import labels_for

PATTERNS = (
    re.compile(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}"),
    re.compile(r"(?<!\d)(?:\+82[- .]?)?0?1[016789][- .]?\d{3,4}[- .]?\d{4}(?!\d)"),
    re.compile(r"(?<!\d)\d{6}[- ]?[1-8]\d{6}(?!\d)"),
    re.compile(r"(?<!\d)(?:\d[ -]?){8,19}(?!\d)"),
    re.compile(r"(?i)(?:password|passwd|otp|token|비밀번호|인증번호|이름|성명|주소)\s*[:=]\s*[^\s,;]+"),
)
NUMERIC_EXTRAS = {"progress", "value", "current", "range_current", "percent", "min", "max"}

def allowed_vision_label(value, vocabulary_key=None):
    label = " ".join(value.strip().lower().split())
    return label if label in labels_for(vocabulary_key) else None


def sanitize_vision_snapshot(vision, vocabulary_key=None):
    if not vision.redaction_applied:
        raise ValueError("Vision masking was not completed")
    elements = []
    for index, element in enumerate(vision.elements):
        label = allowed_vision_label(element.text, vocabulary_key)
        if label is not None:
            # Do not reflect arbitrary provider/element identifiers containing private text.
            elements.append(replace(element, element_id=f"ocr_{index}", text=label,
                role=element.role if element.role in {"slider", "button", "menu_item", "text"} else None))
    lines = tuple(filter(None, (allowed_vision_label(line, vocabulary_key) for line in (vision.ocr_text or "").splitlines())))
    return replace(vision, screenshot_base64=None, mime_type="image/png", provider="on_device_ocr",
        elements=tuple(elements), ocr_text="\n".join(lines) or None, privacy_status="verified")


def redact_text(value):
    if value is None:
        return None
    for pattern in PATTERNS:
        value = pattern.sub("[REDACTED]", value)
    return value


def safe_extras(extras):
    safe = {key: value for key, value in extras.items()
            if key in NUMERIC_EXTRAS and type(value) in (int, float)
            and abs(value) <= 100_000_000 and math.isfinite(value)}
    if type(extras.get('checked')) is bool:
        safe['checked'] = extras['checked']
    if extras.get('heading') is True:
        safe['heading'] = True
    return safe


def sensitive_node(node):
    return ("edittext" in (node.class_name or "").lower()
            or any(node.extras.get(key) for key in ("password", "is_password", "isPassword", "editable", "isEditable")))


def sanitize_request(request, *, vision_vocabulary_key=None):
    from ai_agent.runtime.environment import safe_window_context
    request = replace(request, window_context=safe_window_context(request.window_context))
    nodes = tuple(replace(node,
        text=None if sensitive_node(node) else redact_text(node.text),
        content_description=None if sensitive_node(node) else redact_text(node.content_description),
        extras={} if sensitive_node(node) else safe_extras(node.extras),
        clickable=False if sensitive_node(node) else node.clickable,
        enabled=False if sensitive_node(node) else node.enabled,
    ) for node in request.accessibility_tree)
    goal = replace(request.raw_goal, source_text=redact_text(request.raw_goal.source_text)) if request.raw_goal else None
    vision = request.vision_snapshot
    if vision:
        authorized = vision.user_consented and vision.consent_request_id == request.request_id
        if authorized:
            try:
                vision = sanitize_vision_snapshot(vision, vision_vocabulary_key)
            except Exception:
                # Fail closed, without exception text, provider data, labels or image egress.
                vision = replace(vision, screenshot_base64=None, ocr_text=None, elements=(),
                    provider="local", privacy_status="masking_failed")
        else:
            vision = replace(vision, screenshot_base64=None, user_consented=False,
                provider="local", ocr_text=None, elements=(), privacy_status="unverified")
    return replace(request, raw_goal=goal, accessibility_tree=nodes, vision_snapshot=vision)
