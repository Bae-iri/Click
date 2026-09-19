from hashlib import sha256
import json
import os

from fastapi import HTTPException

from app.clients.checkpoint import SCHEMA_VERSION, capture, graph_version, restore, safe_response
from app.clients.runtime_agent import RuntimeAgentAdapter
from app.clients.turn_executor import execute
from app.services.safety import validate_confirmation, validate_output
from app.repositories.agent_sessions import AgentSessionRepository
from app.repositories.personalization import PersonalizationRepository, UserNotFoundError
from app.schemas.agent_turn import AgentTurnResponse
from app.schemas.agent_turn import AgentCommandResponse, CommandResult, GraphReference
from app.repositories.task_graphs import TaskGraphRepository


class AgentTurnService:
    def __init__(self, session_factory, engine_factory=RuntimeAgentAdapter):
        self.session_factory = session_factory
        self.engine_factory = engine_factory
        self.repository = AgentSessionRepository(session_factory,
            idle_seconds=int(os.getenv("AGENT_SESSION_IDLE_SECONDS", "1800")),
            lease_seconds=int(os.getenv("AGENT_TURN_LEASE_SECONDS", "60")))
        self.timeout_seconds = float(os.getenv("AGENT_TURN_TIMEOUT_SECONDS", "20"))
        self.graphs = TaskGraphRepository(session_factory)
        if not 0 < self.timeout_seconds < self.repository.lease_seconds:
            raise ValueError("Turn timeout must be positive and shorter than the lease")

    def handle_turn(self, request):
        selected_graph = None
        selected_reference = None
        if getattr(request, "command_type", None) in {"start_task", "replace_task"}:
            selected_graph, selected_reference = self.graphs.select(request.goal)
        elif getattr(request, "command_type", None) in {"observe", "respond_confirmation"}:
            details = self.repository.typed_details(request.session_id, request.user_id)
            goal = type("StoredGoal", (), details["goal"])()
            selected_graph, reference = self.graphs.select(goal)
            if details["graph_hash"] and details["graph_hash"] != reference["sha256"]:
                self.repository.conflict("CHECKPOINT_INCOMPATIBLE")
        canonical = json.dumps(request.model_dump(mode="json"), sort_keys=True,
                               separators=(",", ":"), ensure_ascii=False)
        digest = sha256(canonical.encode()).hexdigest()
        claim = self.repository.claim(request, digest)
        if "cached" in claim:
            if claim["graph_version"] is not None and (
                claim["schema_version"] != SCHEMA_VERSION or claim["graph_version"] != graph_version()
            ):
                self.repository.conflict("CHECKPOINT_INCOMPATIBLE")
            return self.personalize(safe_response(AgentTurnResponse.model_validate(claim["cached"])), request.user_id)
        try:
            approved = validate_confirmation(self.session_factory, request, int(self.repository.clock()))
            if claim["checkpoint"] is not None and (
                claim["schema_version"] != SCHEMA_VERSION or claim["graph_version"] != graph_version()
            ):
                self.repository.conflict("CHECKPOINT_INCOMPATIBLE")
            # An isolated runtime per turn prevents cross-request state corruption.
            store = restore(claim["checkpoint"])
            previous = store.get(request.session_id)
            authorization = ((previous.pending_attempt or {}).get("authorization") or {}) if previous else {}
            if authorization and authorization.get("expires_at", 0) <= int(self.repository.clock()):
                store.update(request.session_id, pending_attempt=None, pending_transition_id=None)
            engine = self.engine_factory(session_store=store, task_graph=selected_graph) \
                if selected_graph is not None else self.engine_factory(session_store=store)
            response = safe_response(execute(lambda: engine.handle_turn(request), self.timeout_seconds))
            validate_output(request, response, approved, checkpoint=claim["checkpoint"],
                            now=int(self.repository.clock()))
            response.session_version = claim["version"] + 1
            checkpoint = capture(store, request.session_id)
            if approved and checkpoint and checkpoint.get("pending_attempt"):
                guide = response.guide_command
                checkpoint["pending_attempt"]["authorization"] = {
                    "expires_at": approved["approval_expires_at"],
                    "guide_command_id": guide.guide_command_id, "action_type": guide.action_type,
                    "target_node_id": guide.target_node_id, "target_bounds": list(guide.target_bounds or ()),
                    "task_id": response.task_id, "state_id": response.state_id,
                }
            self.repository.finish(request, claim, response, checkpoint, SCHEMA_VERSION, graph_version())
            if selected_reference is not None:
                self.repository.pin_graph(request.session_id, request.user_id, selected_reference)
        except Exception:
            self.repository.release(request.session_id, claim["token"])
            raise
        # Persist a neutral response; reevaluate consent even on retries.
        return self.personalize(response, request.user_id)

    def handle_command(self, request):
        command = request.command_type
        graph_hash = graph_version()
        if command in {"pause_task", "resume_task", "cancel_task"}:
            canonical = json.dumps(request.model_dump(mode="json"), sort_keys=True,
                separators=(",", ":"), ensure_ascii=False)
            details = self.repository.apply_typed_control(request, graph_hash,
                sha256(canonical.encode()).hexdigest())
            messages = {
                "pause_task": "작업을 잠시 멈췄어요.",
                "resume_task": "작업을 다시 시작했어요. 현재 화면을 확인할게요.",
                "cancel_task": "작업을 취소했어요.",
            }
            return AgentCommandResponse(
                request_id=request.request_id, session_id=request.session_id,
                session_version=details["version"], episode_id=details["episode_id"],
                response_type="control",
                command_result=CommandResult(command_type=command,
                    status="applied" if details["changed"] else "unchanged"),
                accepted_goal_type=details["goal_type"], task_id=details["task_id"],
                graph_reference=GraphReference(task_id=details["task_id"],
                    version=details["graph_version"], sha256=details["graph_hash"]),
                task_status=details["status"], message=messages[command],
            )
        if command == "replace_task":
            current = self.repository.typed_details(request.session_id, request.user_id)
            # Exact replace retries address the newly-created episode; the stored
            # AgentTurn hash still fences reuse with a different request body.
            if current["episode_id"] != request.episode_id:
                if not self.repository.has_request(request.user_id, request.request_id):
                    self.repository.conflict("EPISODE_MISMATCH")
                new_episode = current["episode_id"]
            else:
                new_episode = self.repository.prepare_replace(request)
            request = request.model_copy(update={"episode_id": new_episode, "command_type": "observe"})
        response = self.handle_turn(request)
        details = self.repository.typed_details(request.session_id, request.user_id)
        return AgentCommandResponse(
            request_id=request.request_id, session_id=request.session_id,
            session_version=response.session_version,
            episode_id=details["episode_id"], response_type="guidance",
            command_result=CommandResult(command_type=command, status="applied"),
            accepted_goal_type=details["goal_type"], task_id=response.task_id,
            graph_reference=GraphReference(task_id=response.task_id,
                version=details["graph_version"], sha256=details["graph_hash"] or graph_hash),
            task_status=response.task_status, guide_command=response.guide_command,
            metadata=response.metadata,
        )

    def personalize(self, response, user_id):
        with self.session_factory() as db:
            repository = PersonalizationRepository(db)
            try:
                repository.lock_user(user_id)
                consent = repository.latest_consent(user_id, "personalization")
                if consent is None or not consent.consented:
                    return response
                profile = repository.get_profile(user_id)
            except UserNotFoundError as error:
                raise HTTPException(401) from error
            context = {
                "comprehension_level": profile.comprehension_level,
                "explanation_depth": profile.explanation_depth,
                "prefers_step_by_step": profile.prefers_step_by_step,
                "confidence_score": profile.confidence_score,
            }
            response.metadata["ui_profile"] = context
            message = response.guide_command.message
            if context["comprehension_level"] == "beginner" or context["explanation_depth"] == "detailed":
                response.guide_command.message = f"천천히 한 단계씩 진행할게요. {message}"
            elif context["explanation_depth"] == "concise":
                response.guide_command.message = message.split(".", 1)[0].strip() + ("." if "." in message else "")
            return response
