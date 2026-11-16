"""Shared browsing affordances, not a task graph. Unknown controls cannot gain authority."""
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import unicodedata

from app.services.goal_input_privacy import classification_text

POLICY = json.loads((Path(__file__).parents[3] / 'AI-Agent/goal_catalog/coupang_browse_policy.json').read_text(encoding='utf-8'))
VERSION = POLICY['version']
GOALS = {'coupang.product.search', 'coupang.product.details'}


def enabled():
    return os.getenv('CLICK_COUPANG_BROWSE_ENABLED', '0') == '1'


def resource(value):
    name = value.rsplit('/', 1)[-1]
    return re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', name).lower()


def digest(value):
    return sha256(value.encode('utf-8')).hexdigest()


def query_hash(value):
    return digest(re.sub(r'\s+', '', unicodedata.normalize('NFKC', value)).lower())


def safe_query(value):
    value = value.strip()
    if not value or len(value) > 100 or classification_text(value) != value:
        raise ValueError('검색할 상품 이름만 입력해 주세요. 개인정보는 입력하지 마세요.')
    return value


def explicit_query(source):
    """Only a literal search phrase; unspecified product names remain a question."""
    text = re.sub(r'^\s*쿠팡(?:\s*앱)?(?:에서|으로)?\s*', '', source).strip()
    match = re.fullmatch(r'(.+?)(?:을|를)?\s*(?:검색해\s*줘|검색해\s*주세요|검색|찾아\s*줘|찾아\s*주세요)[.!?\s]*', text)
    if not match:
        return None
    query = match[1].strip()
    if re.sub(r'\s+', '', query) in {'상품','제품','물건','이거','이상품','상품을','제품을','물건을'}:
        return None
    try:
        return safe_query(query)
    except ValueError:
        return None


def node_kind(node):
    name = resource(node.resource_id)
    if any(token in name for token in POLICY['blocked_resource_tokens']):
        return None
    if name in POLICY['search_resources']:
        return 'search'
    if name in POLICY['product_resources'] and node.label and node.clickable:
        return 'product'
    if name in POLICY['results_resources'] and node.scrollable:
        return 'scroll'
    return None


def sanitize(screen, now_ms):
    if screen is None or screen.status != 'public':
        return None
    if screen.package_name != POLICY['package'] or not now_ms - 30000 <= screen.captured_at_ms <= now_ms + 5000:
        return None
    allowed = set().union(*(POLICY[key] for key in ('search_resources', 'product_resources', 'results_resources', 'detail_resources', 'title_resources')))
    for n in screen.nodes:
        if not n.resource_id.startswith(POLICY['package'] + ':id/'):
            return None
        if resource(n.resource_id) not in allowed or any(token in resource(n.resource_id) for token in POLICY['blocked_resource_tokens']):
            return None
        if n.label and (classification_text(n.label) != n.label or any(w in n.label.lower() for w in POLICY['blocked_labels'])):
            return None
    return screen


def page(screen):
    names = {resource(n.resource_id) for n in screen.nodes}
    if names.intersection(POLICY['detail_resources']):
        return 'detail'
    if names.intersection(POLICY['results_resources']):
        return 'results'
    return 'search' if names.intersection(POLICY['search_resources']) else 'unknown'
