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

    def handle_turn(self, request, *, command_context=None):
        selected_graph = None
        selected_reference = None
        if getattr(request, "command_type", None) in {"start_task", "replace_task"}:
            if self.repository.has_request(request.user_id, request.request_id, request.session_id):
                details = self.repository.typed_details(request.session_id, request.user_id)
                selected_graph, selected_reference = (self.graphs.select_pinned(details)
                    if details["graph_hash"] else self.graphs.select(request.goal))
            else:
                selected_graph, selected_reference = self.graphs.select(request.goal)
        elif getattr(request, "command_type", None) in {"observe", "respond_confirmation"}:
            details = self.repository.typed_details(request.session_id, request.user_id)
            goal = type("StoredGoal", (), details["goal"])()
            selected_graph, selected_reference = (self.graphs.select_pinned(details)
                if details["graph_hash"] else self.graphs.select(goal))
            if details["graph_hash"] and details["graph_hash"] != selected_reference["sha256"]:
                self.repository.conflict("CHECKPOINT_INCOMPATIBLE")
        checkpoint_graph_version = graph_version(selected_graph)
        compatible_versions = {checkpoint_graph_version}
        # Existing font sessions used the whole bundled registry hash before DB loading.
        if selected_graph and selected_graph.definition.task_id in {"MVP-01", "MVP-02"}:
            compatible_versions.add(graph_version())
        canonical = json.dumps(request.model_dump(mode="json"), sort_keys=True,
                               separators=(",", ":"), ensure_ascii=False)
        if command_context is not None:
            canonical += "|" + command_context
        digest = sha256(canonical.encode()).hexdigest()
        claim = self.repository.claim(request, digest)
        if "cached" in claim:
            if claim["graph_version"] is not None and (
                claim["schema_version"] != SCHEMA_VERSION or claim["graph_version"] not in compatible_versions
            ):
                self.repository.conflict("CHECKPOINT_INCOMPATIBLE")
            return self.personalize(safe_response(AgentTurnResponse.model_validate(claim["cached"])), request.user_id)
        try:
            approved = validate_confirmation(self.session_factory, request, int(self.repository.clock()))
            if claim["checkpoint"] is not None and (
                claim["schema_version"] != SCHEMA_VERSION or claim["graph_version"] not in compatible_versions
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
            checkpoint = capture(store, request.session_id, selected_graph)
            if approved and checkpoint and checkpoint.get("pending_attempt"):
                guide = response.guide_command
                checkpoint["pending_attempt"]["authorization"] = {
                    "expires_at": approved["approval_expires_at"],
                    "guide_command_id": guide.guide_command_id, "action_type": guide.action_type,
                    "target_node_id": guide.target_node_id, "target_bounds": list(guide.target_bounds or ()),
                    "task_id": response.task_id, "state_id": response.state_id,
                }
            self.repository.finish(request, claim, response, checkpoint, SCHEMA_VERSION,
                checkpoint_graph_version, graph_reference=selected_reference)
        except Exception:
            self.repository.release(request.session_id, claim["token"])
            raise
        # Persist a neutral response; reevaluate consent even on retries.
        return self.personalize(response, request.user_id)

    def handle_command(self, request):
        command = request.command_type
        if command == "resolve_goal":
            from app.services.goal_resolution import GoalResolutionService
            version = self.repository.resolution_version(request)
            return GoalResolutionService(self.graphs).resolve(request, version)
        if command == "respond_result":
            details = self.repository.acknowledge_result(request)
            return AgentCommandResponse(contract_version=request.contract_version,
                request_id=request.request_id, session_id=request.session_id,
                session_version=details["version"], episode_id=details["episode_id"], response_type="control",
                command_result=CommandResult(command_type=command, status="applied"),
                accepted_goal_type=details["goal_type"], task_id=details["task_id"],
                graph_reference=GraphReference(task_id=details["task_id"],
                    version=details["graph_version"], sha256=details["graph_hash"]),
                task_status=details["status"], message="결과를 확인했어요." if request.result_response == "accepted"
                    else "추가 조절을 원하시면 새 요청을 입력해 주세요.",
                metadata={"result_response": request.result_response})
        if command == "adjust_task":
            current = self.repository.typed_details(request.session_id, request.user_id)
            if not current["goal_type"] or current["goal_type"].rsplit(".", 1)[0] != request.goal.goal_type.rsplit(".", 1)[0]:
                raise ValueError("adjust_task must keep the same setting; use replace_task for another goal")
        graph_hash = graph_version()
        if command in {"pause_task", "resume_task", "cancel_task"}:
            pinned = self.repository.typed_details(request.session_id, request.user_id)
            graph_hash = pinned["graph_hash"] or graph_hash
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
                contract_version=request.contract_version,
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
        if command in {"replace_task", "adjust_task"}:
            # Validate the new graph before altering the current episode.
            self.graphs.select(request.goal)
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
        response = self.handle_turn(request, command_context="adjust_task" if command == "adjust_task" else None)
        details = self.repository.typed_details(request.session_id, request.user_id)
        return AgentCommandResponse(
            contract_version=request.contract_version,
            request_id=request.request_id, session_id=request.session_id,
            session_version=response.session_version,
            episode_id=details["episode_id"], response_type="guidance",
            command_result=CommandResult(command_type=command, status="applied"),
            accepted_goal_type=details["goal_type"], task_id=response.task_id,
            graph_reference=GraphReference(task_id=response.task_id,
                version=details["graph_version"], sha256=details["graph_hash"] or graph_hash),
            task_status=response.task_status, state_id=response.state_id,
            step=response.step, guide_command=response.guide_command,
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
            from ai_agent.user_modeling import guidance_context
            difficulty = guidance_context(profile.state_difficulties, response.state_id)
            context["current_state_difficulty"] = difficulty
            response.metadata["ui_profile"] = context
            message = response.guide_command.message
            if difficulty["needs_extra_guidance"] and response.guide_command.requires_user_action:
                response.guide_command.message = (
                    f"천천히 한 단계씩 진행할게요. {message} "
                    "안내한 항목의 이름과 위치를 먼저 확인한 뒤 진행해 주세요.")
            elif context["comprehension_level"] == "beginner" or context["explanation_depth"] == "detailed":
                response.guide_command.message = f"천천히 한 단계씩 진행할게요. {message}"
            elif context["explanation_depth"] == "concise":
                response.guide_command.message = message.split(".", 1)[0].strip() + ("." if "." in message else "")
            return response
