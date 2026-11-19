"""One bounded Jev decision over native public menus, without authored routes."""
from hashlib import sha256
import json
import math
from time import monotonic

import httpx

from ai_agent.runtime.common_screen import STATE, TRANSITION, navigation_nodes, payload_for
from ai_agent.runtime.budget import remaining
from ai_agent.runtime.targeting import target_key
from ai_agent.runtime.types import ActionType, MatchResult
from app.services.goal_jev import _probability
from app.services.goal_llm import _unique_object


def resolve(resolver, *, model, minimum, nodes, raw_nodes, goal, screen_bounds, exclusions, attempt):
    diagnostic = resolver.last_diagnostics
    diagnostic.update(judgment_mode="semantic", question_count=1, candidate_count=0)
    if len({n.node_id for n in nodes}) != len(nodes) or len({n.window_id for n in nodes}) > 1:
        diagnostic['status'] = 'invalid_candidates'
        return None
    candidates = tuple(n for n in navigation_nodes(nodes)
        if target_key(n) not in (exclusions or {}).get(TRANSITION, ())
        and (not screen_bounds or screen_bounds[0] <= (n.bounds[0] + n.bounds[2]) / 2 < screen_bounds[2]
             and screen_bounds[1] <= (n.bounds[1] + n.bounds[3]) / 2 < screen_bounds[3]))
    diagnostic.update(candidate_count=len(candidates), grounded_candidate_count=len(candidates))
    if not candidates:
        diagnostic['status'] = 'no_candidates'
        return None
    if len(nodes) > 160 or len(candidates) > 250:
        diagnostic.update(status='candidate_limit', limit_kind='observation_nodes', limit=160)
        return None
    # All visible candidates participate. Do not sort by goal keywords or a route.
    # Duplicate identities are retained for explicit model abstention, never merged.
    previous = {'executed': True} if attempt and attempt.get('event_key') else None
    payload = payload_for(model, goal, nodes, candidates, previous, raw_nodes)
    encoded = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode()
    diagnostic.update(payload_bytes=len(encoded), exported_node_count=len(payload['state']['screen']['items']))
    if len(encoded) > 65536:
        diagnostic.update(status='input_too_large', limit_kind='payload_bytes', limit=65536)
        return None
    diagnostic.update(called=True, status='provider_error')
    started = monotonic()
    try:
        response = (resolver._sender or resolver._post)(payload, timeout=remaining(8.0))
        if response.status_code != 200 or len(response.content) > 65536:
            return None
        diagnostic['status'] = 'invalid_response'
        body = json.loads(response.content, object_pairs_hook=_unique_object)
        if body['model'] != model or set(body['answers']) != {'screen'}:
            return None
        answer = body['answers']['screen']
        if set(answer) != {'type', 'choice', 'confidence', 'probabilities'} or answer['type'] != 'choice':
            return None
        choice, probabilities = answer['choice'], answer['probabilities']
        options = payload['questions']['screen']['criteria']
        if not isinstance(probabilities, dict) or set(probabilities) != set(options) or choice not in options:
            return None
        if (not all(_probability(p) for p in probabilities.values())
                or not math.isclose(math.fsum(probabilities.values()), 1, abs_tol=1e-4)
                or not _probability(answer['confidence'])):
            return None
        top = probabilities[choice]
        if any(p >= top for key, p in probabilities.items() if key != choice):
            return None
        normalized = (top - 1 / len(options)) / (1 - 1 / len(options))
        confidence = min(answer['confidence'], normalized)
        diagnostic.update(choice=choice, choice_count=len(options), raw_confidence=answer['confidence'],
            top_probability=top, normalized_probability=normalized, confidence=confidence,
            probability_margin=top - max(p for key, p in probabilities.items() if key != choice),
            hold_reason='none')
        if confidence < minimum:
            diagnostic.update(status='low_confidence', hold_reason=(
                'both_below_minimum' if answer['confidence'] < minimum and normalized < minimum else
                'raw_confidence_below_minimum' if answer['confidence'] < minimum else 'normalized_probability_below_minimum'))
            return None
        if choice == 'insufficient_evidence':
            diagnostic.update(status='abstained', hold_reason='insufficient_evidence')
            return None
        node = candidates[int(choice.removeprefix('candidate_'))]
        # A probability cannot resolve multiple native controls with identical meaning.
        if sum(target_key(other) == target_key(node) for other in candidates) != 1:
            diagnostic.update(status='ambiguous_candidates')
            return None
        diagnostic.update(status='selected', chosen_state=STATE, chosen_transition=TRANSITION,
            chosen_transition_hash=sha256(TRANSITION.encode()).hexdigest(),
            chosen_target_hash=sha256(target_key(node).encode()).hexdigest())
        return MatchResult(STATE, STATE, ActionType.TAP, node.node_id, node.label, node.bounds,
            'jev_choice', confidence, transition_id=TRANSITION, target_key=target_key(node))
    except TimeoutError:
        diagnostic['status'] = 'deadline_exceeded'
    except httpx.TimeoutException:
        diagnostic['status'] = 'timeout'
    except httpx.TransportError:
        diagnostic['status'] = 'network_error'
    except (ValueError, KeyError, TypeError, AttributeError, IndexError, RecursionError):
        pass
    finally:
        diagnostic['provider_latency_ms'] = round((monotonic() - started) * 1000, 3)
    return None
