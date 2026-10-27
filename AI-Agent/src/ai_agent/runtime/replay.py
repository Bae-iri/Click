"""Opt-in, vocabulary-redacted capture and deterministic recovery replay.

Captured decisions are observations, never gold labels. Independently reviewed
`expected` fields drive evaluation. No screenshots or raw goals are exported.
"""
from dataclasses import asdict
from hashlib import sha256
import json
from pathlib import Path
import re
from uuid import uuid4

from ai_agent.runtime.keywords import normalize_text
from ai_agent.runtime.privacy import safe_extras, sensitive_node
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext, TypedGoal, UserActionEvent, UserActionType


SCHEMA = "ai1-recovery-replay.v1"
UI_VOCABULARY = {"배경화면", "배경화면 및 스타일", "홈 화면", "밝기", "음량", "확인", "취소",
                 "연결", "블루투스", "다크 모드", "글자 크기와 스타일", "화면 크게 작게"}


def graph_digest(graph):
    return sha256(json.dumps(graph.to_dict(), sort_keys=True, ensure_ascii=False,
                             separators=(",", ":")).encode()).hexdigest()


class ReplayRecorder:
    """Bounded in-memory diagnostic capture; exporting/persisting is explicit."""
    def __init__(self, graph, max_turns=100):
        if max_turns <= 0:
            raise ValueError("max_turns must be positive")
        self.graph, self.max_turns = graph, max_turns
        self.salt, self.turns, self.guides = uuid4().hex, [], {}
        self.session_key = None
        self.vocabulary = set(UI_VOCABULARY)
        self.packages, self.resources = set(), {"android:id/title", "android:id/summary"}
        for state in graph.states.values():
            self.vocabulary.update(state.required_keywords + state.optional_keywords)
            for group in state.required_keyword_groups:
                self.vocabulary.update(group)
            self.packages.update(state.package_candidates)
        for transition in graph.transitions:
            self.vocabulary.update(transition.target_keywords)
            self.packages.update(transition.selector.package_candidates)
            self.resources.update(transition.selector.resource_ids)
        for words in graph.synonyms.values():
            self.vocabulary.update(words)
        self.vocabulary = {normalize_text(word) for word in self.vocabulary if word}

    def identifier(self, value):
        return None if value is None else "id_" + sha256((self.salt + value).encode()).hexdigest()[:24]

    def label(self, value):
        normalized = normalize_text(value)
        if normalized in self.vocabulary:
            return normalized
        # Remove all free text, retaining only non-overlapping approved UI phrases.
        spans, end = [], 0
        matches = sorted((match.start(), -len(word), match.end(), word)
            for word in self.vocabulary for match in re.finditer(r"(?<!\w)" + re.escape(word) + r"(?!\w)", normalized))
        for start, _, stop, word in matches:
            if start >= end:
                spans.append(word)
                end = stop
        return " ".join(spans) or ("[redacted]" if value else None)

    def record(self, request, response):
        if len(self.turns) >= self.max_turns:
            return
        if response.task_id != self.graph.definition.task_id:
            raise ValueError("Replay graph does not match the active task")
        session_key = self.identifier(json.dumps([request.session_id, request.episode_id]))
        if self.session_key is not None and self.session_key != session_key:
            raise ValueError("A recorder belongs to one episode")
        self.session_key = session_key
        nodes = []
        for node in request.accessibility_tree:
            data = asdict(node)
            data.update(node_id=self.identifier(node.node_id), parent_id=self.identifier(node.parent_id),
                text=None if sensitive_node(node) else self.label(node.text),
                content_description=None if sensitive_node(node) else self.label(node.content_description),
                clickable=False if sensitive_node(node) else node.clickable,
                enabled=False if sensitive_node(node) else node.enabled,
                package_name=node.package_name if node.package_name in self.packages else "redacted.package",
                resource_id=node.resource_id if node.resource_id in self.resources else None,
                class_name=node.class_name if node.class_name in {
                    "android.widget.TextView", "android.widget.LinearLayout", "android.widget.FrameLayout",
                    "android.widget.Button", "android.widget.SeekBar", "android.widget.Switch",
                    "android.widget.RadioButton", "android.widget.EditText", "android.widget.ListView",
                    "androidx.recyclerview.widget.RecyclerView"} else "android.view.View",
                extras={} if sensitive_node(node) else safe_extras(node.extras))
            nodes.append(data)
        step = {"observation": {"nodes": nodes, "screen_bounds": request.screen_bounds,
            "active_window_id": request.active_window_id, "observation_id": self.identifier(request.observation_id or request.request_id),
            "captured_at_ms": request.captured_at_ms}, "observed_result": {
                "state_id": response.current_state_id,
                "target_node_id": self.identifier(response.guide_command.target_node_id),
                "recovery": response.guide_command.render_policy.get("recovery"),
                "command_type": response.guide_command.command_type.value}}
        if request.user_confirmation:
            step["confirmation"] = request.user_confirmation
        event = request.user_action_event
        if event:
            step["action"] = {"type": event.action_type.value,
                "guide_turn": self.guides.get(event.guide_command_id),
                "target_node_id": self.identifier(event.target_node_id),
                "occurred_at_ms": event.occurred_at_ms, "event_id": self.identifier(event.event_id),
                "binding_valid": (event.session_id in {None, request.session_id}
                                  and event.episode_id in {None, request.episode_id})}
        from ai_agent.runtime.verification import font_scale
        scale = font_scale(request.applied_settings)
        if scale is not None:
            step["applied_settings"] = {"source": "android.provider.Settings.System.FONT_SCALE", "font_scale": scale}
        self.guides[response.guide_command.guide_command_id] = len(self.turns)
        self.turns.append(step)

    def export(self):
        return json.loads(json.dumps({"schema_version": SCHEMA, "source": "redacted_diagnostic",
            "redaction": "vocabulary_only_lossy", "task_id": self.graph.definition.task_id,
            "graph_hash": graph_digest(self.graph), "turns": self.turns}))


def evaluate_cases(cases, graphs):
    from ai_agent.runtime.engine import ClickAgentEngine
    from ai_agent.runtime.similarity import KeywordFallbackSimilarityScorer
    metrics = dict(target_checks=0, false_target_count=0, termination_checks=0, false_termination_count=0,
                   completion_checks=0, false_completion_count=0, recovery_cases=0, recovered_cases=0)
    violations = []
    decisions = []
    for case_index, case in enumerate(cases):
        if case["schema_version"] != SCHEMA:
            raise ValueError("Unsupported replay schema")
        graph = graphs[case["task_id"]]
        if case.get("graph_hash") and case["graph_hash"] != graph_digest(graph):
            raise ValueError("Replay graph hash mismatch")
        engine = ClickAgentEngine(task_graphs={graph.definition.task_id: graph},
                                  similarity_scorer=KeywordFallbackSimilarityScorer())
        responses, recovery_seen, recovered = [], False, False
        for index, step in enumerate(case["turns"]):
            obs = step["observation"]
            action = step.get("action")
            event = None
            if action:
                guide_turn = action.get("guide_turn")
                guide = responses[guide_turn].guide_command if isinstance(guide_turn, int) and 0 <= guide_turn < index else None
                event = UserActionEvent(action_type=UserActionType(action["type"]),
                    guide_command_id=guide.guide_command_id if guide else None,
                    target_node_id=action.get("target_node_id", guide.target_node_id if guide else None),
                    occurred_at_ms=action.get("occurred_at_ms"), event_id=action.get("event_id", f"e{index}"),
                    session_id=None if action.get("binding_valid", True) else "mismatched-session")
            definition = graph.definition
            result = engine.handle_turn(AgentTurnRequest(request_id=f"r{index}", session_id="replay", raw_goal=None,
                typed_goal=TypedGoal(definition.goal_type, definition.target_app, definition.task_type,
                    definition.required_slots, "", 1.0, task_id=definition.task_id,
                    is_supported=True, required_slots_filled=True) if index == 0 else None,
                device_context=DeviceContext(platform="android"),
                accessibility_tree=tuple(AccessibilityNode(**{**node, "bounds_in_screen": tuple(node["bounds_in_screen"])
                    if node.get("bounds_in_screen") else None}) for node in obs["nodes"]),
                screen_bounds=tuple(obs["screen_bounds"]) if obs.get("screen_bounds") else None,
                active_window_id=obs.get("active_window_id"), observation_id=obs.get("observation_id", f"o{index}"),
                captured_at_ms=obs.get("captured_at_ms"), user_action_event=event,
                user_confirmation=step.get("confirmation"), applied_settings=step.get("applied_settings", {})))
            responses.append(result)
            command = result.guide_command
            recovery = command.render_policy.get("recovery", {})
            if recovery_seen and command.target_node_id and not recovery.get("recoverable"):
                recovered = True
            recovery_seen |= bool(recovery.get("recoverable"))
            actual = {"state_id": result.current_state_id, "target_node_id": command.target_node_id,
                "terminal": command.command_type.value in {"done", "blocked"},
                "done": command.command_type.value == "done", "next_action": recovery.get("next_action"),
                "reason_code": recovery.get("reason_code")}
            decisions.append({"case": case_index, "turn": index, "target_node_id": command.target_node_id,
                              "action_type": command.action_type.value})
            expected = step.get("expected", {})
            for field, wanted in expected.items():
                if field not in actual:
                    raise ValueError("Unsupported replay expectation")
                if actual[field] != wanted:
                    violations.append({"case": case_index, "turn": index, "field": field,
                                       "expected": wanted, "actual": actual[field]})
            for field, checks, errors in (("target_node_id", "target_checks", "false_target_count"),
                    ("terminal", "termination_checks", "false_termination_count"), ("done", "completion_checks", "false_completion_count")):
                if field in expected:
                    metrics[checks] += 1
                    metrics[errors] += int(actual[field] is not None and bool(actual[field]) and actual[field] != expected[field])
        if case.get("expect_recovery"):
            metrics["recovery_cases"] += 1
            metrics["recovered_cases"] += int(recovered)
            if not recovered:
                violations.append({"case": case_index, "field": "recovery", "expected": True, "actual": False})
    return {"metrics": metrics, "violations": violations, "decisions": decisions}


def main():
    import argparse
    from ai_agent.runtime.tasks import TASK_GRAPHS
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path)
    args = parser.parse_args()
    report = evaluate_cases(json.loads(args.fixture.read_text(encoding="utf-8")), TASK_GRAPHS)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return int(bool(report["violations"]))


if __name__ == "__main__":
    raise SystemExit(main())
