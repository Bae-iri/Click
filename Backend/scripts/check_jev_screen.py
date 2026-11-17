"""Check the primary AI1 Jev path with a synthetic screen, never device/user data."""
import argparse
import json
import os
from pathlib import Path
import sys

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent' / 'src'))

from app.clients.runtime_agent import RuntimeAgentAdapter
from ai_agent.runtime import AccessibilityNode, DeviceContext, RawGoalInput
from ai_agent.runtime.types import AgentTurnRequest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path)
    args = parser.parse_args(argv)
    if args.env_file is not None:
        if not args.env_file.is_file():
            print('CONFIG_ERROR: env file does not exist.')
            return 2
        from dotenv import load_dotenv
        load_dotenv(args.env_file, override=False)
    if os.getenv('AI1_UI_SELECTOR_PROVIDER', '').strip().lower() != 'jev':
        print('CONFIG_ERROR: set AI1_UI_SELECTOR_PROVIDER=jev in the Backend environment.')
        return 2
    try:
        engine = RuntimeAgentAdapter().runtime
    except ValueError:
        print('CONFIG_ERROR: check TYPESAFE_API_KEY, pinned JEV_MODEL, JEV_MIN_CONFIDENCE, '
              'JEV_SCREEN_JUDGMENT_MODE and JEV_CHOICE_TEMPERATURE.')
        return 2
    nodes = tuple(AccessibilityNode(node_id=identifier, package_name='com.android.settings', text=label,
        class_name='android.widget.TextView', clickable=identifier != 'title',
        bounds_in_screen=(24, 40 + index * 200, 1056, 120 + index * 200))
        for index, (identifier, label) in enumerate((
            ('title', '설정'), ('display', '디스플레이'), ('accessibility', '접근성'))))
    result = engine.handle_turn(AgentTurnRequest(request_id='synthetic-jev-check', session_id='synthetic-jev-check',
        raw_goal=RawGoalInput('글자 크기 키워줘', 'ko-KR', DeviceContext(platform='android')),
        accessibility_tree=nodes, screen_bounds=(0, 0, 1080, 2400), captured_at_ms=1000))
    diagnostic = {**result.debug['ui_selection'], 'match_method': result.match_result.match_method,
                  'target_node_id': result.guide_command.target_node_id}
    print(json.dumps(diagnostic, ensure_ascii=False, indent=2))
    return 0 if (diagnostic.get('mode') == 'primary' and diagnostic['status'] == 'selected'
                 and diagnostic['target_node_id'] == 'display') else 1


if __name__ == '__main__':
    raise SystemExit(main())
