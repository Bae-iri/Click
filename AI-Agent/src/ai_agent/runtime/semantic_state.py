"""Optional semantic disambiguation, grounded in this observation and approved graph."""
from ai_agent.runtime.classifier import _screen_titles
from ai_agent.runtime.keywords import any_phrase_matches
from ai_agent.runtime.matcher import match_next_ui


def resolve_state(nodes, graph, resolver, *, previous_state_id=None, evidence=None,
                  excluded_target_keys=None):
    if resolver is None or not nodes or len({n.window_id for n in nodes}) > 1:
        return None
    vocabulary = set()
    for state in graph.states.values():
        vocabulary.update(state.required_keywords)
        vocabulary.update(state.optional_keywords)
        for group in state.required_keyword_groups:
            vocabulary.update(group)
    for words in graph.synonyms.values():
        vocabulary.update(words)
    for edge in graph.transitions:
        vocabulary.update(edge.target_keywords)
    titles = {n.node_id for n in _screen_titles(nodes)}
    projected, refs = [], {}
    for node in nodes:
        if not node.enabled or node.node_id.startswith('vision:'):
            continue
        terms = sorted(word for word in vocabulary if any_phrase_matches(
            ' '.join(node.label_parts or (node.label,)), (word,)))
        if not terms or node.structure_role == 'description':
            continue
        ref = f'n{len(projected)}'
        refs[node.node_id] = ref
        projected.append({'id': ref, 'terms': terms, 'role': node.role,
                          'title': node.node_id in titles})
    candidates = []
    for state in graph.states.values():
        if state.verification_only or state.state_id in {'S0_UNKNOWN', graph.definition.done_state}:
            continue
        match = match_next_ui(state.state_id, nodes, graph, excluded_target_keys=excluded_target_keys)
        if match.fallback_reason or match.target_node_id not in refs:
            continue
        candidates.append({'state_id': state.state_id, 'name': state.name,
                           'target_id': refs[match.target_node_id]})
    if not candidates or not projected or len(projected) > 64:
        return None
    # No raw UI strings, resource IDs, screenshots or user identifiers leave runtime.
    payload = {'previous_state_id': previous_state_id if previous_state_id in graph.states else None,
               'candidates': candidates, 'nodes': projected}
    try:
        answer = resolver.resolve(payload)
        if not isinstance(answer, dict) or set(answer) != {'state_id', 'evidence_ids'}:
            return None
        selected = next((c for c in candidates if c['state_id'] == answer['state_id']), None)
        ids = answer['evidence_ids']
        if (selected is None or not isinstance(ids, list) or not ids or len(ids) > len(projected)
                or any(not isinstance(ref, str) or ref not in refs.values() for ref in ids)
                or selected['target_id'] not in ids):
            return None
    except Exception:
        return None
    if evidence is not None:
        evidence.update(state_id=selected['state_id'], state_score=0.0,
                        ambiguous=False, title_conflict=False, settings_subpage=False,
                        reason_code='STATE_IDENTIFIED', semantic_resolution=True)
    return selected['state_id']
