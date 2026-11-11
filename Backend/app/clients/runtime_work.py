"""Serializable, database-free runtime work. Only the parent commits a result."""
from dataclasses import dataclass
from app.clients.checkpoint import capture, restore, safe_response
from app.clients.runtime_agent import RuntimeAgentAdapter

@dataclass
class RuntimeWork:
    request: object
    graph: object
    checkpoint: dict | None
    vision_consent_request_id: str | None = None

    def __call__(self):
        store = restore(self.checkpoint)
        engine = RuntimeAgentAdapter(session_store=store, task_graph=self.graph)
        engine.vision_consent_request_id = self.vision_consent_request_id
        response = safe_response(engine.handle_turn(self.request))
        return {"response": response.model_dump(mode="json"),
                "checkpoint": capture(store, self.request.session_id, self.graph),
                "execution_event": getattr(engine, "execution_event", None)}
