"""Small semantic login options, independent of government24's native resource IDs."""
from pathlib import Path
import json, os, re, unicodedata
POLICY = json.loads((Path(__file__).parents[3]/'AI-Agent/goal_catalog/gov24_login_policy.json').read_text(encoding='utf-8'))
VERSION = POLICY['version']
OPTIONS = POLICY['options']

def enabled():
    return os.getenv('CLICK_GOV24_GUIDE_ENABLED', '1') == '1'

def requested(source):
    text = normalize(source)
    return ('정부24' in text or '정부이십사' in text) and any(w in text for w in ('로그인','인증','접속')) and not any(w in text for w in ('하지마','하지않','취소','말고'))

def normalize(text):
    return re.sub(r'[^가-힣a-z0-9]', '', unicodedata.normalize('NFKC', text).lower())

def sanitize(screen, now_ms):
    if screen is None or not now_ms-30000 <= screen.captured_at_ms <= now_ms+5000:
        return None
    if screen.status == 'handoff':
        if screen.handoff_reason != 'external_auth' and screen.package_name != POLICY['package']:
            return None
        return screen
    if screen.status != 'public' or screen.package_name != POLICY['package']:
        return None
    for node in screen.nodes:
        spec = OPTIONS.get(node.option_id)
        if not spec or node.label != spec['label'] or node.resource_id or not node.clickable or node.scrollable:
            return None
    return screen

def projected_answer(raw):
    # Never send arbitrary speech, account identifiers or numbers to the model.
    # Keep only known public option aliases and grammatical uncertainty/negation.
    words = {a for spec in OPTIONS.values() for a in spec['aliases']}
    words |= {str(i)+'번' for i in range(1,25)}
    words |= {'첫번째','첫 번째','두번째','두 번째','세번째','세 번째','네번째','네 번째'}
    words |= {'안','대신','있어','있는데','있고','없는데','없고','쓰고','쓰는데','할래','할게','원해'}
    words |= {'말고','아니','안돼','안해','없어','없어요','모르겠','모르겠어요','몰라','둘다','둘 다'}
    pattern = '|'.join(re.escape(w) for w in sorted(words,key=len,reverse=True))
    return ' '.join(m.group() for m in re.finditer(pattern, raw, flags=re.I))[:160]
