"""Versioned runtime state, with no raw goal or screen labels."""
from dataclasses import asdict
from hashlib import sha256
import json

from ai_agent.runtime.session import InMemorySessionStore
from ai_agent.runtime.tasks import TASK_GRAPHS
from ai_agent.runtime.types import ScreenSnapshot, TypedGoal

SCHEMA_VERSION = 1


def graph_version():
    content = {key: graph.to_dict() for key, graph in TASK_GRAPHS.items()}
    return sha256(json.dumps(content, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def restore(payload):
    store = InMemorySessionStore()
    if payload:
        state = dict(payload)
        state["typed_goal"] = TypedGoal(**state["typed_goal"])
        state["failed_node_ids"] = tuple(state["failed_node_ids"])
        if state["last_snapshot"]:
            state["last_snapshot"] = ScreenSnapshot(**state["last_snapshot"])
        store.start(**state)
    return store


def capture(store, session_id):
    state = store.get(session_id)
    if state is None:
        return None
    payload = asdict(state)
    payload["typed_goal"]["source_text"] = ""
    # Current supported task slots are constrained by the selected graph.
    graph = TASK_GRAPHS.get(state.task_id)
    if graph is None:
        graph = next((g for g in TASK_GRAPHS.values() if g.definition.task_id == state.task_id), None)
    if graph is None:
        raise ValueError("Unknown checkpoint task")
    payload["typed_goal"]["slots"] = dict(graph.definition.required_slots)
    payload["last_user_action_event"] = None
    return payload


def safe_response(response):
    # These fields can originate in UI content or external-provider diagnostics.
    discarded = {"source_text", "label_sample", "screenshot_base64", "image_base64",
                 "ocr_text", "elements", "text", "content_description", "reason"}

    def clean(value):
        if isinstance(value, dict):
            return {key: None if key == "target_label" else clean(item)
                    for key, item in value.items() if key not in discarded}
        if isinstance(value, list):
            return [clean(item) for item in value]
        return value

    return type(response).model_validate(clean(response.model_dump(mode="json")))
