"""Record primary Jev decisions on a synthetic screen without performing clicks."""
import argparse
from datetime import datetime, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import sys
from uuid import uuid4

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / "AI-Agent" / "src"))

from app.clients.runtime_agent import RuntimeAgentAdapter
from app.services.screen_jev import JevScreenResolver
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext, TypedGoal
from ai_agent.telemetry.trace_logger import diagnostic_payload


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--attempts", type=int, default=5, choices=range(1, 11))
    parser.add_argument("--fresh-sessions", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    if args.env_file is not None:
        if not args.env_file.is_file():
            print("CONFIG_ERROR: env file does not exist.")
            return 2
        from dotenv import load_dotenv
        load_dotenv(args.env_file, override=False)
    if os.getenv("AI1_UI_SELECTOR_PROVIDER", "").strip().lower() != "jev":
        print("CONFIG_ERROR: set AI1_UI_SELECTOR_PROVIDER=jev.")
        return 2
    try:
        _, model, _ = JevScreenResolver.configuration()
        engine = RuntimeAgentAdapter().runtime
    except ValueError:
        print("CONFIG_ERROR: check TYPESAFE_API_KEY, pinned JEV_MODEL and JEV_MIN_CONFIDENCE.")
        return 2
    graph = next(g for g in engine.task_graphs.values()
                 if g.definition.goal_type == "settings.font_size.increase")
    goal = TypedGoal(goal_type=graph.definition.goal_type, target_app=graph.definition.target_app,
        task_type=graph.definition.task_type, slots=graph.definition.required_slots,
        source_text="", confidence=1, task_id=graph.definition.task_id,
        is_supported=True, required_slots_filled=True)
    transitions = {sha256(t.transition_id.encode()).hexdigest(): t for t in graph.transitions}
    nodes = [AccessibilityNode(node_id="list", package_name="com.android.settings", scrollable=True,
        class_name="androidx.recyclerview.widget.RecyclerView", bounds_in_screen=(0, 100, 1080, 2200))]
    for index, (identifier, label) in enumerate((("title", "설정"), ("display", "디스플레이"),
                                                ("accessibility", "접근성"))):
        nodes.append(AccessibilityNode(node_id=identifier, package_name="com.android.settings", text=label,
            class_name="android.widget.TextView", clickable=identifier != "title",
            parent_id=None if identifier == "title" else "list",
            bounds_in_screen=(24, 40 + index * 200, 1056, 120 + index * 200)))
    report = {"source": "synthetic_screen_real_jev", "model": model,
        "started_at": datetime.now(timezone.utc).isoformat(), "actual_clicks": False,
        "mode": "fresh_sessions" if args.fresh_sessions else "repeated_observations", "observations": []}
    session_id = "synthetic-" + uuid4().hex
    for turn in range(args.attempts):
        if args.fresh_sessions:
            session_id = "synthetic-" + uuid4().hex
        previous = engine.session_store.get(session_id)
        previous_action = previous.pending_attempt if previous else None
        result = engine.handle_turn(AgentTurnRequest(request_id=uuid4().hex, session_id=session_id,
            typed_goal=goal if previous is None else None, raw_goal=None, accessibility_tree=tuple(nodes),
            device_context=DeviceContext(platform="android"), screen_bounds=(0, 0, 1080, 2400),
            observation_id=uuid4().hex, captured_at_ms=1000 * (turn + 1)))
        selection = diagnostic_payload(result.debug).get("ui_selection", {})
        observation = {"attempt": turn + 1, "previous_action_present": bool(previous_action),
            "previous_action_event_received": bool(previous_action and previous_action.get("event_key")),
            "ui_selection": selection, "action_type": result.guide_command.action_type.value}
        transition = transitions.get(selection.get("chosen_transition_hash"))
        if transition is not None:
            # Decode only against the approved graph, never provider or screen text.
            observation["chosen_transition"] = transition.transition_id
            observation["chosen_target_names"] = list(transition.target_keywords)
        report["observations"].append(observation)
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output is not None:
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if all(o["ui_selection"].get("status") in {
        "selected", "low_confidence", "waiting", "abstained"} for o in report["observations"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
