"""Versioned runtime state, with no raw goal or screen labels."""
from dataclasses import asdict
from hashlib import sha256
import json
from ai_agent.telemetry.trace_logger import diagnostic_payload
from ai_agent.runtime.privacy import redact_text

from ai_agent.runtime.session import InMemorySessionStore
from app.task_graphs import approved_graphs
from ai_agent.runtime.types import ScreenSnapshot, TypedGoal

SCHEMA_VERSION = 3
COMPATIBLE_SCHEMA_VERSIONS = {2, 3}
TASK_GRAPHS = approved_graphs()


def graph_version(task_graph=None):
    if task_graph is not None:
        encoded = json.dumps(task_graph.to_dict(), sort_keys=True,
            ensure_ascii=False, separators=(",", ":"))
        return sha256(encoded.encode("utf-8")).hexdigest()
    content = {key: graph.to_dict() for key, graph in TASK_GRAPHS.items()}
    return sha256(json.dumps(content, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def restore(payload):
    store = InMemorySessionStore()
    if payload:
        state = dict(payload)
        state["typed_goal"] = TypedGoal(**state["typed_goal"])
        state["failed_node_ids"] = tuple(state["failed_node_ids"])
        state["processed_event_keys"] = tuple(state.get("processed_event_keys", ()))
        state["state_candidates"] = tuple(state.get("state_candidates", ()))
        state["failed_target_keys"] = {key: tuple(value) for key, value in state.get("failed_target_keys", {}).items()}
        if state["last_snapshot"]:
            state["last_snapshot"] = ScreenSnapshot(**state["last_snapshot"])
        store.start(**state)
    return store


def capture(store, session_id, task_graph=None):
    state = store.get(session_id)
    if state is None:
        return None
    payload = asdict(state)
    payload["typed_goal"]["source_text"] = ""
    # Current supported task slots are constrained by the selected graph.
    graph = task_graph or TASK_GRAPHS.get(state.task_id)
    if graph is None:
        graph = next((g for g in TASK_GRAPHS.values() if g.definition.task_id == state.task_id), None)
    if graph is None:
        raise ValueError("Unknown checkpoint task")
    if graph.definition.task_id != state.task_id:
        raise ValueError("Checkpoint graph task mismatch")
    payload["typed_goal"]["slots"] = dict(graph.definition.required_slots)
    payload["last_user_action_event"] = None
    return payload


def safe_response(response):
    # These fields can originate in UI content or external-provider diagnostics.
    discarded = {"source_text", "label_sample", "screenshot_base64", "image_base64",
                 "ocr_text", "elements", "text", "content_description", "reason", "ui_profile"}

    def clean(value):
        if isinstance(value, dict):
            return {key: item if key in {"task_outcome", "technical_outcome", "result_prompt", "checklist"} else None if key == "target_label" else diagnostic_payload(item) if key == "debug"
                    else redact_text(item) if key == "message" and isinstance(item, str) else clean(item)
                    for key, item in value.items() if key not in discarded}
        if isinstance(value, list):
            return [clean(item) for item in value]
        return value

    return type(response).model_validate(clean(response.model_dump(mode="json")))
