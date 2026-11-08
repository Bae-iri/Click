"""Native Wi-Fi master-switch identity and independently observed adapter state."""
from ai_agent.runtime.keywords import normalize_text

PREDICATES = {'target_checked_on', 'target_checked_off'}
MASTER_IDS = {'com.android.settings:id/switch_widget', 'android:id/switch_widget',
    'com.android.settings:id/switch_bar', 'com.android.settings:id/switchbar',
    'com.android.settings:id/switch_main', 'com.samsung.android.settings:id/switch_widget'}
WIFI_NAMES = {normalize_text(w) for w in ('Wi-Fi', '와이파이', 'Wi-Fi 사용', '와이파이 사용', 'Use Wi-Fi')}
STATUS_NAMES = {normalize_text(w) for w in ('사용 중', '사용 안 함', '사용', '켜짐', '꺼짐', 'On', 'Off', 'Wi-Fi')}


def is_wifi_transition(transition, graph):
    return graph.definition.required_slots.get('setting') == 'wifi' and transition.verification_predicate in PREDICATES


def adapter_state(settings):
    if (settings or {}).get('wifi_source') != 'android.net.wifi.WifiManager.getWifiState':
        return None
    value = (settings or {}).get('wifi_state')
    return value if type(value) is int and 0 <= value <= 4 else None


def target_allowed(node, nodes):
    from ai_agent.runtime.classifier import _screen_titles
    if (not node.checkable or type(node.checked) is not bool or node.source_confidence is not None
            or node.node_id.startswith('vision:')):
        return False
    titles = [n for n in _screen_titles(nodes) if n.package_name == node.package_name and n.window_id == node.window_id
        and not n.node_id.startswith('vision:') and n.source_confidence is None]
    names = {normalize_text(n.label) for n in titles}
    if not names.intersection(WIFI_NAMES | {'internet', '인터넷'}):
        return False
    labels = {normalize_text(w) for w in node.label_parts or (node.label,) if w}
    if labels.intersection(WIFI_NAMES):
        return True
    # An anonymous generic switch can be the master only on a dedicated Wi-Fi page,
    # never on Internet, which may expose mobile-data and Wi-Fi together.
    return bool(names.intersection(WIFI_NAMES) and node.resource_id in MASTER_IDS
        and labels.issubset(STATUS_NAMES) and sum(n.role == 'toggle' and n.checkable
            and n.package_name == node.package_name and n.window_id == node.window_id for n in nodes) == 1)


def already_satisfied(graph, nodes, settings):
    from ai_agent.runtime.targeting import eligible_nodes, selector_evidence
    from ai_agent.runtime.types import VerificationResult, VerificationStatus
    for edge in graph.transitions:
        if not is_wifi_transition(edge, graph):
            continue
        expected = edge.verification_predicate == 'target_checked_on'
        targets = [n for n in eligible_nodes(edge, nodes, graph)
            if selector_evidence(n, edge, nodes, graph)[0] >= 0.7]
        if (len(targets) == 1 and targets[0].checked is expected
                and adapter_state(settings) == (3 if expected else 1)):
            return VerificationResult(VerificationStatus.SUCCESS, edge.from_state, edge.to_state,
                evidence='wifi_already_set', task_completed=True, count_attempt=False)
    return None
