"""Screen state classification."""

from __future__ import annotations

from ai_agent.runtime.keywords import any_keyword_matches, any_phrase_matches, normalize_text
from ai_agent.runtime.targeting import eligible_nodes, selector_evidence
from ai_agent.runtime.types import DeviceContext, NormalizedNode, TaskGraph, TaskState


def classify_screen_state(
    nodes: tuple[NormalizedNode, ...],
    graph: TaskGraph,
    *,
    device_context: DeviceContext | None = None,
    previous_state_id: str | None = None,
    evidence: dict | None = None,
) -> str:
    candidates = []
    titles = _screen_titles(nodes)

    for state in graph.states.values():
        if state.verification_only or state.state_id in {"S0_UNKNOWN", graph.definition.done_state}:
            continue

        relevant = tuple(node for node in nodes if not state.package_candidates
                         or node.package_name in state.package_candidates)
        packages = {node.package_name for node in relevant if node.package_name}
        package_match = bool(packages) or bool(device_context
            and device_context.app_package in state.package_candidates)
        if state.package_candidates and not relevant:
            continue
        screen_text = " ".join(" ".join(node.label_parts) if node.label_parts else node.label
                               for node in relevant if node.structure_role != 'description')
        roles = {node.role for node in relevant}
        required_groups = _required_groups(state, graph)
        if required_groups and not _all_required_groups_match(screen_text, required_groups):
            continue

        required_score = 0.55 if required_groups else 0.0
        optional_score = _optional_keyword_score(screen_text, state.optional_keywords)
        role_score = 0.2 if roles.intersection(state.role_hints) else 0.0
        package_score = 0.1 if package_match else 0.0
        continuity_score = _continuity_score(graph, previous_state_id, state.state_id)
        score = required_score + optional_score + role_score + package_score + continuity_score

        title_match = bool(required_groups and any(
            title in relevant and all(any_phrase_matches(title.label, group) for group in required_groups)
            for title in titles))
        # Some settings pages combine navigation rows and the actual adjustment
        # control. A uniquely grounded slider is stronger evidence than a broad
        # menu title such as Samsung's "글자 크기와 스타일".
        control_match = any(
            transition.target_role in {'slider', 'toggle'} and transition.action_type.value in {'drag', 'tap'}
            and transition.completes_task
            and sum(selector_evidence(node, transition, relevant, graph)[0] >= 0.7
                    for node in eligible_nodes(transition, relevant, graph)) == 1
            for transition in graph.transitions_from(state.state_id))
        if score >= state.confidence_threshold:
            candidates.append({"state_id": state.state_id, "score": round(min(score, 1.0), 4),
                               "title_match": title_match, "control_match": control_match})

    # A page title outranks words in row descriptions, even after a skipped step.
    candidates.sort(key=lambda item: (-int(item["control_match"]), -int(item["title_match"]),
                                      -item["score"], item["state_id"]))
    ambiguous = bool(len(candidates) > 1
        and candidates[0]["control_match"] == candidates[1]["control_match"]
        and candidates[0]["title_match"] == candidates[1]["title_match"]
        and candidates[0]["score"] - candidates[1]["score"] < 0.025)
    relevant_titles = [title for title in titles if any(
        title.package_name in state.package_candidates for state in graph.states.values())]
    title_conflict = bool(relevant_titles and candidates
                         and not any(item["title_match"] or item["control_match"] for item in candidates))
    chosen = candidates[0] if candidates and not ambiguous and not title_conflict else None
    state_id = chosen["state_id"] if chosen else "S0_UNKNOWN"
    if evidence is not None:
        evidence.update(state_score=chosen["score"] if chosen else 0.0, state_id=state_id,
                        candidates=candidates[:3], ambiguous=ambiguous, title_conflict=title_conflict,
                        settings_subpage=bool(state_id == "S0_UNKNOWN" and relevant_titles
                            and any(title.package_name in {"com.android.settings", "com.samsung.android.settings"}
                                and title.label.strip().casefold() not in {"설정", "settings"}
                                for title in relevant_titles)
                            and any(node.actionable and node.label.strip().casefold() in
                                {"뒤로", "뒤로 이동", "이전", "이전 화면", "navigate up", "back"}
                                and node.package_name in {"com.android.settings", "com.samsung.android.settings"}
                                for node in nodes)),
                        reason_code="STATE_AMBIGUOUS" if ambiguous else "STATE_IDENTIFIED" if chosen else "STATE_UNKNOWN")
    return state_id


def _screen_titles(nodes):
    """Top standalone text per app/window; list labels and summaries aren't titles."""
    titles = {}
    for node in nodes:
        if (not node.label or node.role != "text" or not node.enabled or not node.bounds
                or node.structure_role in {"control_label", "description"}):
            continue
        left, top, right, bottom = node.bounds
        if any(other.node_id != node.node_id and other.bounds
               and other.package_name == node.package_name and other.window_id == node.window_id
               and (other.actionable or other.scrollable)
               and other.bounds[0] <= left and other.bounds[1] <= top
               and other.bounds[2] >= right and other.bounds[3] >= bottom for other in nodes):
            continue
        key = (node.package_name, node.window_id)
        if key not in titles or (top, left, node.node_id) < (titles[key].bounds[1], titles[key].bounds[0], titles[key].node_id):
            titles[key] = node
    return tuple(titles.values())


def _required_groups(state: TaskState, graph: TaskGraph) -> tuple[tuple[str, ...], ...]:
    groups = state.required_keyword_groups or tuple((keyword,) for keyword in state.required_keywords)
    # Screen recognition and target matching must understand the same graph-owned aliases.
    return tuple(tuple(dict.fromkeys((*group, *(word for words in graph.synonyms.values()
        if set(map(normalize_text, group)) & set(map(normalize_text, words)) for word in words))))
        for group in groups)


def _all_required_groups_match(
    screen_text: str,
    required_groups: tuple[tuple[str, ...], ...],
) -> bool:
    return all(any_keyword_matches(screen_text, group) for group in required_groups)


def _optional_keyword_score(screen_text: str, optional_keywords: tuple[str, ...]) -> float:
    if not optional_keywords:
        return 0.0
    matches = sum(
        1 for keyword in optional_keywords if any_keyword_matches(screen_text, (keyword,))
    )
    return min(0.15, 0.05 * matches)


def _continuity_score(
    graph: TaskGraph,
    previous_state_id: str | None,
    candidate_state_id: str,
) -> float:
    if not previous_state_id:
        return 0.0
    if previous_state_id == candidate_state_id:
        return 0.05
    next_states = {
        transition.to_state for transition in graph.transitions_from(previous_state_id)
    }
    return 0.05 if candidate_state_id in next_states else 0.0
