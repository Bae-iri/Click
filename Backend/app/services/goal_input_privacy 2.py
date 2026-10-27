"""Best-effort classification minimization, not a guarantee of anonymization.

Only the model-bound copy is changed. Queue/session retention is a separate policy.
"""
import re
import unicodedata

from ai_agent.runtime.privacy import redact_text


REDACTED = "[REDACTED]"
# Bare names/addresses without a label cannot reliably be recognized by regex.
_PRIVATE_CLAUSE = re.compile(
    r"(?i)(?:(?:제|내|저의)\s*(?:이름|성명)|성명|(?<!앱 )(?<!앱)이름|"
    r"집\s*주소|주소|비밀번호|비번|인증번호|주민등록번호|계좌번호|카드번호|"
    r"password|passwd|otp|api[_ -]?key|access[_ -]?token)"
    r"\s*(?:은|는|이|가|:|=)\s*.*?"
    r"(?:(?:인데|이고|이며|입니다|이에요|예요)(?=\s|[,.;]|$)|(?=[,;\n]|$))"
)
_URL = re.compile(r"(?i)\b(?:https?://|www\.)[^\s<>]+")
_PHONE = re.compile(r"(?<!\d)(?:\+82[- .]?)?(?:0?1[016789]|0[2-6][1-5]?)[- .]?\d{3,4}[- .]?\d{4}(?!\d)")
_LONG_NUMBER = re.compile(r"(?<!\d)(?:\d[ -]?){7,19}(?!\d)")


def classification_text(source: str) -> str | None:
    if not isinstance(source, str) or not source.strip() or len(source) > 2000:
        return None
    text = unicodedata.normalize("NFKC", source)
    text = "".join(c for c in text if unicodedata.category(c) != "Cf")
    text = _PRIVATE_CLAUSE.sub(REDACTED, text)
    text = _URL.sub(REDACTED, text)
    text = _PHONE.sub(REDACTED, text)
    text = _LONG_NUMBER.sub(REDACTED, text)
    text = redact_text(text)
    text = " ".join("".join(c if c.isprintable() else " " for c in text).split())
    if not re.search(r"[가-힣A-Za-z0-9]", text.replace(REDACTED, "")):
        return None
    return text
