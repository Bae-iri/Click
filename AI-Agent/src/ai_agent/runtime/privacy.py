"""Conservative text redaction and input-field removal before inference."""
from dataclasses import replace
import math
import re

PATTERNS = (
    re.compile(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}"),
    re.compile(r"(?<!\d)(?:\+82[- .]?)?0?1[016789][- .]?\d{3,4}[- .]?\d{4}(?!\d)"),
    re.compile(r"(?<!\d)\d{6}[- ]?[1-8]\d{6}(?!\d)"),
    re.compile(r"(?<!\d)(?:\d[ -]?){8,19}(?!\d)"),
    re.compile(r"(?i)(?:password|passwd|otp|token|비밀번호|인증번호|이름|성명|주소)\s*[:=]\s*[^\s,;]+"),
)
NUMERIC_EXTRAS = {"progress", "value", "current", "range_current", "percent", "min", "max"}


def redact_text(value):
    if value is None:
        return None
    for pattern in PATTERNS:
        value = pattern.sub("[REDACTED]", value)
    return value


def safe_extras(extras):
    return {key: value for key, value in extras.items()
            if key in NUMERIC_EXTRAS and type(value) in (int, float)
            and abs(value) <= 100_000_000 and math.isfinite(value)}


def sensitive_node(node):
    return ("edittext" in (node.class_name or "").lower()
            or any(node.extras.get(key) for key in ("password", "is_password", "isPassword", "editable", "isEditable")))


def sanitize_request(request):
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
        vision = replace(vision, screenshot_base64=None,
            ocr_text=redact_text(vision.ocr_text) if vision.user_consented else None,
            elements=tuple(replace(e, text=redact_text(e.text)) for e in vision.elements) if vision.user_consented else ())
    return replace(request, raw_goal=goal, accessibility_tree=nodes, vision_snapshot=vision)
