"""Bind confirmation to owner (session FK), version, command and observation."""
from hashlib import sha256
import json
from uuid import uuid4
from fastapi import HTTPException
from app.models.lifecycle import SafetyConfirmation
from app.models.agent_session import AgentSession


def screen_hash(request, *, include_vision=True):
    tree = request.accessibility_tree.model_dump(mode="json") if request.accessibility_tree else None
    vision = request.vision_snapshot.model_dump(mode="json") if include_vision and request.vision_snapshot else None
    if tree:
        for key in ("captured_at", "captured_at_ms", "observation_id"):
            tree.pop(key, None)
    if vision:
        vision.pop("captured_at_ms", None)
        vision.pop("observation_id", None)
    return sha256(json.dumps([tree, vision], sort_keys=True).encode()).hexdigest()


def conflict():
    raise HTTPException(409, headers={"X-Conflict-Code": "CONFIRMATION_INVALID"})


def validate_confirmation(factory, request, now):
    # A client boolean is not authorization. Every analysis needs a live challenge.
    if request.vision_snapshot is not None and request.user_confirmation != "accepted":
        conflict()
    if not request.user_confirmation:
        if request.confirmation_id:
            conflict()
        return None
    # Rejection is always safe, including before an approval was issued.
    failure_report = request.user_confirmation == "rejected" and getattr(request, "vision_failure", None)
    if request.user_confirmation == "rejected" and not failure_report:
        with factory() as db:
            row = db.get(SafetyConfirmation, request.session_id)
            if row and row.command.get("purpose") == "vision" and row.id == request.confirmation_id and row.version == request.expected_version:
                return {**row.command, "approval_expires_at": row.expires_at}
        return None
    with factory() as db:
        row = db.get(SafetyConfirmation, request.session_id)
        vision_consent = row is not None and row.command.get("purpose") == "vision"
        if (row is None or row.id != request.confirmation_id or row.version != request.expected_version
                or row.expires_at <= now or (not failure_report and row.screen_hash != screen_hash(request, include_vision=not vision_consent))
                or getattr(request, "raw_goal", None)):
            conflict()
        if failure_report and not vision_consent:
            conflict()
        if vision_consent and not failure_report:
            validate_vision_snapshot(request, row, now)
        elif request.vision_snapshot is not None:
            conflict()
        provenance=getattr(request,"approval_provenance",None)
        audit={"source":"legacy_unspecified"} if provenance is None else provenance.model_dump(exclude_none=True)
        if provenance is not None and provenance.source=="delegated_goal":
            session=db.get(AgentSession,request.session_id)
            goal=(session.goal or {}) if session else {}
            slots=goal.get("slots",{})
            if (vision_consent or session is None or goal.get("goal_type")!=provenance.goal_type
                    or slots.get("setting") not in {"font_size","brightness","volume"}
                    or slots.get("direction") not in {"increase","decrease"}
                    or row.command.get("proposed_action")!="drag"
                    or (session.workflow or {}).get("delegated_actions",0)>=1
                    or not provenance.granted_at_ms<=now*1000+5000
                    or provenance.expires_at_ms<=now*1000
                    or provenance.granted_at_ms<session.created_at*1000-5000):
                conflict()
        return {**row.command,"approval_expires_at":row.expires_at,"provenance":audit}


def validate_vision_snapshot(request, row, now):
    snapshot, tree = request.vision_snapshot, request.accessibility_tree
    if (snapshot is None or not snapshot.user_consented or tree is None
            or snapshot.screenshot_base64 is not None
            or not snapshot.observation_id or snapshot.observation_id != tree.observation_id
            or not snapshot.package_name or snapshot.package_name != tree.package_name
            or snapshot.active_window_id != tree.active_window_id
            or snapshot.screen_revision != tree.screen_revision
            or snapshot.screen_width != tree.screen_width or snapshot.screen_height != tree.screen_height
            or snapshot.captured_at_ms is None
            or not (row.expires_at - 120) * 1000 <= snapshot.captured_at_ms <= (now + 5) * 1000):
        conflict()
    if any(element.bounds_in_screen is not None and not (
            0 <= element.bounds_in_screen[0] < element.bounds_in_screen[2] <= tree.screen_width
            and 0 <= element.bounds_in_screen[1] < element.bounds_in_screen[3] <= tree.screen_height)
            for element in snapshot.elements):
        conflict()


def validate_output(request, response, approved, *, checkpoint=None, now=0):
    # Permission to read a screen never authorizes tapping or changing settings.
    vision_response = bool(approved and approved.get("purpose") == "vision")
    vision_failure_report = vision_response and bool(getattr(request, "vision_failure", None))
    if approved and approved.get("purpose") == "vision":
        approved = None
    guide = response.guide_command
    if approved and approved.get("provenance"):
        response.metadata["approval_provenance"]=dict(approved["provenance"])
    if vision_failure_report:
        response.metadata["vision_failure"] = request.vision_failure
    failure = getattr(request, "vision_failure", None)
    if request.user_confirmation == "rejected" and not failure:
        from ai_agent.runtime.termination import user_declined
        goal = response.metadata.get("typed_goal") or {}
        previous = response.metadata.get("technical_outcome") or {}
        termination = user_declined((goal.get("slots") or {}).get("setting"),
            previously_changed=previous.get("status") == "CHANGED",
            scope="vision" if vision_response else "action")
        response.task_status = "CANCELLED"
        guide.command_type, guide.action_type = "cancelled", "observe_only"
        guide.proposed_action = None
        guide.requires_user_action = False
        guide.target_node_id = guide.target_bounds = guide.target_label = None
        guide.safety_level, guide.message = "LOW_RISK", termination["message"]
        guide.render_policy = {"termination": termination}
        response.metadata["termination"] = termination
        # No old recovery/result prompt may schedule another turn after refusal.
        for key in ("recovery", "result_prompt", "follow_up", "approval_provenance"):
            response.metadata.pop(key, None)
        return
    if failure:
        response.metadata["failure_classification"] = {
            "category": "PRIVACY_PROTECTION" if failure == "masking_failed" else "ENVIRONMENT",
            "code": "VISION_" + failure.upper(),
            "counts_as_failure": failure in {"capture_failed", "ocr_failed", "masking_failed"}}
    if guide.command_type == "result_confirmation" and not guide.requires_user_action:
        return
    if approved and (guide.target_node_id != approved.get("target_node_id") or guide.action_type != approved.get("proposed_action")):
        conflict()
    if approved and (response.task_id != approved.get("task_id") or response.state_id != approved.get("state_id")):
        conflict()
    if approved and list(guide.target_bounds or ()) != list(approved.get("target_bounds") or ()):
        conflict()
    if guide.safety_level == "BLOCKED" and guide.command_type != "blocked":
        raise HTTPException(502)
    if guide.safety_level == "CONFIRM_REQUIRED" and guide.command_type not in ("confirmation", "blocked") and not approved:
        # Re-show only the exact operation already authorized and persisted by BE.
        pending = (checkpoint or {}).get("pending_attempt") or {}
        authorization = pending.get("authorization") or {}
        expected = {"guide_command_id": guide.guide_command_id, "action_type": guide.action_type,
                    "target_node_id": guide.target_node_id, "target_bounds": list(guide.target_bounds or ()),
                    "task_id": response.task_id, "state_id": response.state_id}
        if authorization.get("provenance"):
            response.metadata["approval_provenance"]=dict(authorization["provenance"])
        if (authorization.get("expires_at", 0) <= now
                or any(authorization.get(key) != value for key, value in expected.items())):
            raise HTTPException(502)
    bounds = guide.target_bounds
    tree, vision = request.accessibility_tree, request.vision_snapshot
    width = tree.screen_width if tree else vision.screen_width
    height = tree.screen_height if tree else vision.screen_height
    if bounds and (not width or not height or not (0 <= bounds[0] < bounds[2] <= width and 0 <= bounds[1] < bounds[3] <= height)):
        raise HTTPException(502)


def save_confirmation(db, request, response, now):
    row = db.get(SafetyConfirmation, request.session_id)
    vision_consent = "vision_fallback" in response.guide_command.render_policy
    if response.guide_command.command_type != "confirmation" or (response.guide_command.proposed_action is None and not vision_consent):
        if row:
            db.delete(row)
        return
    values = dict(id=str(uuid4()), version=response.session_version, expires_at=now + 120,
                  screen_hash=screen_hash(request, include_vision=not vision_consent), command={**response.guide_command.model_dump(mode="json"),
                      "purpose": "vision" if vision_consent else "action",
                      "task_id": response.task_id, "state_id": response.state_id})
    if row is None:
        row = SafetyConfirmation(session_id=request.session_id, **values)
        db.add(row)
    else:
        for key, value in values.items():
            setattr(row, key, value)
    response.metadata["confirmation"] = {"id": row.id, "expires_at": row.expires_at,
        "purpose": row.command["purpose"], "scope": "single_analysis" if vision_consent else "action",
        "guide_command_id": response.guide_command.guide_command_id, "version": response.session_version}
