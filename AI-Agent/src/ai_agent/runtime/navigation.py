"""Retire interrupted attempts; navigation is never a failed guided action."""
from dataclasses import fields, replace
from hashlib import sha256

LAUNCHERS = {"com.android.launcher3", "com.google.android.apps.nexuslauncher",
             "com.sec.android.app.launcher", "com.miui.home", "com.huawei.android.launcher",
             "com.oppo.launcher", "com.vivo.launcher", "org.lineageos.trebuchet"}


def apply_navigation(store, request, session, *, persist=True):
    """Compatibility wrapper for callers that explicitly commit navigation alone."""
    updated = navigation_result(request, session)
    if updated is session or not persist:
        return updated
    return store.update(request.session_id, **session_fields(updated))


def session_fields(session):
    return {field.name: getattr(session, field.name) for field in fields(session)
            if field.name != "session_id"}


def navigation_result(request, session):
    """Compute interruption state without exposing a partially committed turn."""
    event = request.user_action_event
    if not session or not event or event.action_type.value not in {"back_navigated", "home_navigated"}:
        return session
    if event.session_id not in {None, request.session_id} or event.occurred_at_ms is None:
        return session
    baseline = session.last_snapshot.captured_at_ms if session.last_snapshot else None
    if baseline is not None and event.occurred_at_ms <= baseline:
        return session
    key = sha256(('navigation:' + (event.event_id or f'{event.action_type.value}:{event.occurred_at_ms}')).encode()).hexdigest()
    if key in session.processed_event_keys:
        return session
    home = event.action_type.value == "home_navigated"
    if home:
        visible = tuple(node for node in request.accessibility_tree if node.visible_to_user)
        if not any(node.package_name in LAUNCHERS for node in visible):
            return session
        if any(any(word in ((node.class_name or '') + ' ' + (node.resource_id or '')).lower()
                   for word in ('overview', 'recents', 'recent_tasks')) for node in visible):
            return session
    changes = dict(pending_attempt=None, pending_selection=None, pending_transition_id=None,
                   pending_confirmation_transition_id=None, last_guide_command_id=None,
                   last_target_node_id=None, recovery_state={}, vision_state={}, last_snapshot=None)
    if home:
        changes.update(current_state_id="S0_UNKNOWN", observed_path=(), progress_steps=(),
                       task_completed=False, technical_outcome={}, failed_node_ids=(),
                       failed_target_keys={}, transition_attempts={}, total_failed_attempts=0,
                       recovery_count=0)
    changes['processed_event_keys'] = (*session.processed_event_keys, key)[-128:]
    return replace(session, **changes)
