"""Backend input interpretation, kept outside the screen execution runtime."""
import json
from pathlib import Path
import re

from app.schemas.agent_turn import AgentGoalResolutionResponse, TypedGoalRequest
from app.repositories.task_graphs import NoCompatibleGraphError, AmbiguousGraphError
from app.services.goal_classifier import configured_goal_classifier


def normalize(value):
    return re.sub(r"[^0-9a-z가-힣]", "", value.lower())


def font_goal(direction):
    """Canonical typed goal for existing help cards; this does not authorize execution."""
    if direction not in {"increase", "decrease"}:
        raise ValueError("Invalid font-size direction")
    catalog = json.loads((Path(__file__).parents[1] / "task_graphs/goal_catalog.json").read_text(encoding="utf-8"))
    entry = next(g for g in catalog["goals"] if g["goal_type"] == "settings.font_size." + direction)
    return TypedGoalRequest(catalog_version=catalog["version"], goal_type=entry["goal_type"],
        target_app=entry["target_app"], slots=entry["slots"])


class GoalResolutionService:
    def __init__(self, graphs, llm=None):
        self.graphs = graphs
        self.llm = llm if llm is not None else configured_goal_classifier()
        self.catalog = json.loads((Path(__file__).parents[1] / "task_graphs" / "goal_catalog.json").read_text(encoding="utf-8"))

    def resolve(self, request, version):
        text = normalize(request.source_text)
        base = dict(request_id=request.request_id, session_id=request.session_id, session_version=version)
        if any(term in text for term in ("하지마", "하지말", "지마", "지말", "지않", "안해", "말아")):
            return AgentGoalResolutionResponse(**base, resolution_status="needs_clarification",
                message="변경을 원하시는지 다시 확인해 주세요. 아직 작업을 시작하지 않았어요.")
        scored = []
        for goal in self.catalog["goals"]:
            lengths = [max((len(normalize(word)) for word in group if normalize(word) and normalize(word) in text), default=0)
                for group in goal["keyword_groups"]]
            if not lengths or not all(lengths):
                continue
            score = len(lengths) * 100 + sum(lengths)
            if any(text == normalize(word) for word in goal.get("utterances", [])):
                score += 1000
            package = request.device_context.foreground_package or ""
            if (goal["target_app"] == "android_settings" and package == "com.android.settings") or (
                    goal["target_app"] == "coupang" and "coupang" in package.lower()):
                score += 1
            scored.append((score, goal))
        if not scored:
            # Only genuine keyword misses reach the LLM. Negation, tied matches and
            # known but unsupported goals keep their deterministic outcomes.
            eligible = []
            for goal in self.catalog["goals"]:
                if goal.get("execution_status") != "approved":
                    continue
                try:
                    self.graphs.select(TypedGoalRequest(catalog_version=self.catalog["version"],
                        goal_type=goal["goal_type"], target_app=goal["target_app"], slots=goal["slots"]))
                except (ValueError, NoCompatibleGraphError, AmbiguousGraphError):
                    continue
                eligible.append(goal)
            selected = self.llm.select(request.source_text, [
                {key: goal[key] for key in ("goal_type", "display_name", "target_app", "slots")}
                for goal in eligible])
            matches = [goal for goal in eligible if goal["goal_type"] == selected]
            if not matches:
                return AgentGoalResolutionResponse(**base, resolution_status="needs_clarification",
                    message="대상과 원하는 변경을 구체적으로 말씀해 주세요.")
        else:
            top = max(score for score, _ in scored)
            matches = [goal for score, goal in scored if score == top]
        if len(matches) != 1:
            return AgentGoalResolutionResponse(**base, resolution_status="needs_clarification",
                message="여러 목표로 해석할 수 있어요. 대상과 방향을 확인해 주세요.")
        match = matches[0]
        try:
            typed = TypedGoalRequest(catalog_version=self.catalog["version"],
                goal_type=match["goal_type"], target_app=match["target_app"], slots=match["slots"])
            self.graphs.select(typed)
        except (ValueError, NoCompatibleGraphError, AmbiguousGraphError):
            return AgentGoalResolutionResponse(**base, resolution_status="unsupported",
                display_name=match["display_name"], message="이 요청의 실행 안내는 아직 지원하지 않아요.")
        return AgentGoalResolutionResponse(**base, resolution_status="confirmation_required",
            goal=typed, display_name=match["display_name"], message=f"{match['display_name']} 안내를 시작할까요?")
