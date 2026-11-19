"""Small semantic login options, independent of government24's native resource IDs."""
from pathlib import Path
import json, os, re, unicodedata
from app.services.goal_input_privacy import classification_text
POLICY = json.loads((Path(__file__).parents[3]/'AI-Agent/goal_catalog/gov24_login_policy.json').read_text(encoding='utf-8'))
VERSION = POLICY['version']
OPTIONS = POLICY['options']
CIVIL = POLICY['civil_navigation']
CIVIL_OPTIONS = CIVIL['options']
CIVIL_SCREEN_OPTIONS = {**CIVIL_OPTIONS, **OPTIONS}
from app.task_graphs.gov24 import GRAPHS as CIVIL_GRAPHS, validate as validate_baselines
validate_baselines(CIVIL_OPTIONS)
if set(CIVIL['services']) != set(CIVIL_GRAPHS) or any(
        CIVIL['services'][goal]['graph_id'] != graph['task_id'] or CIVIL['services'][goal]['label'] != graph['label']
        for goal, graph in CIVIL_GRAPHS.items()):
    raise ValueError('Government24 service catalog and baseline graphs disagree')
CIVIL['graphs'] = CIVIL_GRAPHS


def civil_goal(source):
    text = normalize(source)
    if any(word in text for word in ('신청내역', '발급내역', '처리상태', '하지마', '하지않', '취소', '말고')):
        return None
    matches = [goal for goal, service in CIVIL['services'].items()
               if any(normalize(alias) in text for alias in service['aliases'])]
    # '등본' in a named land/vehicle service is not a resident-copy request.
    specific = [goal for goal in matches if goal != CIVIL['goal_type']]
    if specific:
        matches = specific
    return matches[0] if len(matches) == 1 else None


def civil_requested(source):
    return civil_goal(source) == CIVIL['goal_type']

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
        spec = (CIVIL_SCREEN_OPTIONS if civil else OPTIONS).get(node.option_id)
        if (not spec or node.label != spec['label'] or node.resource_id or node.scrollable
                or node.clickable != (spec['group'] != 'marker')):
            return None
    if screen.auth_state == 'signed_in' and any(n.option_id == 'entry_login' for n in screen.nodes):
        return None
    if screen.auth_state == 'signed_out' and sum(n.option_id == 'entry_login' for n in screen.nodes) != 1:
        return None
    return screen


def question_fingerprint(screen, *, ordered=True):
    """Bind choices to public semantics; rebind the chosen native target on every answer."""
    from hashlib import sha256
    keys = [n.option_id for n in screen.nodes]
    value = [screen.package_name, screen.auth_state, keys if ordered else sorted(keys)]
    return sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def navigation_limit(state, request, screen, *, civil=False):
    specs = CIVIL_SCREEN_OPTIONS if civil else OPTIONS
    keys = [node.option_id for node in screen.nodes]
    allowed = set(OPTIONS)
    if civil:
        allowed |= {edge['target_option'] for edge in CIVIL_GRAPHS[state['goal']['goal_type']]['transitions']}
    candidates = {key for key in keys if key in allowed and keys.count(key) == 1
                  and specs[key]['group'] in {'entry', 'method', 'provider', 'navigation'}}
    if not candidates:
        return False
    signature = question_fingerprint(screen, ordered=False)
    turns = state.get('navigation_turns', 0)
    if (state.get('public_scene') != signature or request.action is not None
            or request.answer is not None or request.choice is not None):
        turns += 1
    state.update(navigation_turns=turns, public_scene=signature)
    return turns > POLICY['max_turns']

def preference_text(raw):
    # Keep grammar, negation, corrections, and feedback intact. Minimize private
    # identifiers using the same boundary as ordinary goal interpretation.
    return classification_text(raw)
