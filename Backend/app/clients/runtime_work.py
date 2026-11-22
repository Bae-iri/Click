"""Serializable, database-free runtime work. Only the parent commits a result."""
from dataclasses import dataclass
from app.clients.checkpoint import capture, restore, safe_response
from app.clients.runtime_agent import RuntimeAgentAdapter
from app.clients.runtime_timing import phase

@dataclass
class RuntimeWork:
    request: object
    graph: object
    checkpoint: dict | None
    vision_consent_request_id: str | None = None
    learned_routes: tuple = ()

    def __call__(self):
        phase('checkpoint_restore')
        store = restore(self.checkpoint)
        phase('engine_init')
        engine = RuntimeAgentAdapter(session_store=store, task_graph=self.graph, learned_routes=self.learned_routes)
        engine.vision_consent_request_id = self.vision_consent_request_id
        response = safe_response(engine.handle_turn(self.request))
        phase('checkpoint_capture')
        return {"response": response.model_dump(mode="json"),
                "checkpoint": capture(store, self.request.session_id, self.graph),
                "execution_event": getattr(engine, "execution_event", None)}
