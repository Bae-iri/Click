"""Small semantic login options, independent of government24's native resource IDs."""
from pathlib import Path
import json, os, re, unicodedata
from app.services.goal_input_privacy import classification_text
POLICY = json.loads((Path(__file__).parents[3]/'AI-Agent/goal_catalog/gov24_login_policy.json').read_text(encoding='utf-8'))
VERSION = POLICY['version']
OPTIONS = POLICY['options']
CIVIL = POLICY['civil_navigation']
CIVIL_OPTIONS = CIVIL['options']


def civil_requested(source):
    text = normalize(source)
    return ((any(word in text for word in ('정부24', '정부이십사', 'gov24', '주민등록등본', '주민등록표등본')))
            and any(word in text for word in ('주민등록등본', '주민등록표등본', '등본'))
            and not any(word in text for word in ('초본', '등본말고', '토지', '건축물', '신청내역', '발급내역', '처리상태')))

def enabled():
    return os.getenv('CLICK_GOV24_GUIDE_ENABLED', '1') == '1'

def requested(source):
    text = normalize(source)
    return ('정부24' in text or '정부이십사' in text or 'gov24' in text) and any(w in text for w in ('로그인','인증','접속')) and not any(w in text for w in ('하지마','하지않','취소','말고'))

def normalize(text):
    return re.sub(r'[^가-힣a-z0-9]', '', unicodedata.normalize('NFKC', text).lower())

def sanitize(screen, now_ms, *, civil=False):
    if screen is None or not now_ms-30000 <= screen.captured_at_ms <= now_ms+5000:
        return None
    if screen.status == 'handoff':
        if screen.handoff_reason != 'external_auth' and screen.package_name != POLICY['package']:
            return None
        return screen
    if screen.status != 'public' or screen.package_name != POLICY['package']:
        return None
    for node in screen.nodes:
        spec = (CIVIL_OPTIONS if civil else OPTIONS).get(node.option_id)
        if (not spec or node.label != spec['label'] or node.resource_id or node.scrollable
                or node.clickable != (spec['group'] != 'marker')):
            return None
    return screen

def preference_text(raw):
    # Keep grammar, negation, corrections, and feedback intact. Minimize private
    # identifiers using the same boundary as ordinary goal interpretation.
    return classification_text(raw)
