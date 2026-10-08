"""Agent(2): evidence-based, user-specific guidance modeling."""

DIFFICULTY_EVENTS = {"guide_step_failed", "help_opened", "guide_repeated", "user_confused"}


def update_difficulties(current, event_type, state_id):
    """Return a new snapshot; duration alone never implies difficulty."""
    result = {key: dict(value) for key, value in current.items()}
    if not state_id or event_type not in DIFFICULTY_EVENTS | {"guide_step_completed"}:
        return result
    evidence = result.setdefault(state_id, {"difficulty_count": 0, "completion_count": 0,
        "consecutive_difficulties": 0, "last_event": event_type})
    evidence["last_event"] = event_type
    if event_type in DIFFICULTY_EVENTS:
        evidence["difficulty_count"] += 1
        evidence["consecutive_difficulties"] += 1
    else:
        evidence["completion_count"] += 1
        evidence["consecutive_difficulties"] = 0
    return result


def guidance_context(difficulties, state_id):
    evidence = difficulties.get(state_id, {})
    return {"state_id": state_id, **evidence,
        "needs_extra_guidance": evidence.get("consecutive_difficulties", 0) >= 2}
