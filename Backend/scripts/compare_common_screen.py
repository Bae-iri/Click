"""Compare finite-route and common-screen inputs on declared synthetic scenes.

No device actions. Default is payload-only; --live explicitly calls Jev. These
examples test candidate coverage, not cross-device product accuracy.
"""
import argparse
import json
from pathlib import Path
import sys
from time import monotonic

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent/src'))

import httpx
from app.services.screen_jev import JevScreenResolver
from app.task_graphs import approved_graphs
from ai_agent.runtime.common_screen import PROFILE
from ai_agent.runtime.engine import ClickAgentEngine
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext, TypedGoal


def scenes():
    return (
        ('known_settings', 'brightness', '설정', ('디스플레이', '소리 및 진동', '연결'), 0),
        ('unlisted_readability', 'font_size', '기기 환경', ('알림 관리', '텍스트 보기 설정', '음향 설정'), 1),
        ('unlisted_display', 'brightness', '기기 맞춤', ('네트워크', '화면의 밝기와 색상', '소리 설정'), 1),
        ('unlisted_audio', 'volume', '기기 환경', ('오디오 및 재생', '화면 맞춤', '연결 관리'), 0),
    )


def native_nodes(title, labels):
    def node(identifier, **values):
        return AccessibilityNode(node_id=identifier, package_name='com.android.settings',
            bounds_in_screen=values.pop('bounds_in_screen', (0, 100, 1080, 2200)), **values)
    result = [node('title', text=title, resource_id='android:id/toolbar_title',
                   class_name='android.widget.TextView', bounds_in_screen=(0, 0, 1080, 90)),
              node('list', scrollable=True, class_name='android.widget.ListView')]
    for index, label in enumerate(labels):
        result.extend((node(f'row{index}', parent_id='list', clickable=True,
                            class_name='android.widget.LinearLayout', bounds_in_screen=(0, 200+index*200, 1080, 350+index*200)),
                       node(f'label{index}', parent_id=f'row{index}', text=label, resource_id='android:id/title',
                            class_name='android.widget.TextView', bounds_in_screen=(20, 220+index*200, 800, 300+index*200))))
    return tuple(result)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--env-file', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(argv)
    if args.env_file:
        from dotenv import load_dotenv
        load_dotenv(args.env_file, override=False)
    if args.live:
        JevScreenResolver.configuration()
    else:
        import os
        os.environ.setdefault('TYPESAFE_API_KEY', 'payload-probe-only')
    report = {'schema_version': 'common-screen-comparison.v1', 'source': 'synthetic_public_screens',
              'live_provider': args.live, 'device_actions': False, 'cases': []}
    graphs = approved_graphs()
    for name, setting, title, labels, expected in scenes():
        graph = graphs[f'settings.{setting}.increase']
        d = graph.definition
        goal = TypedGoal(d.goal_type, d.target_app, d.task_type, d.required_slots, '', 1,
            task_id=d.task_id, is_supported=True, required_slots_filled=True)
        case = {'case': name, 'expected_public_menu': f'row{expected}'}
        for profile in ('finite', 'common'):
            calls = []
            provider = JevScreenResolver()
            def send(payload, **kwargs):
                calls.append({'payload_bytes': len(json.dumps(payload, ensure_ascii=False).encode()),
                              'questions': len(payload['questions'])})
                return provider._post(payload, **kwargs) if args.live else httpx.Response(503)
            engine = ClickAgentEngine(task_graphs={d.task_id: graph}, screen_resolver=JevScreenResolver(send))
            started = monotonic()
            result = engine.handle_turn(AgentTurnRequest(name+'-'+profile, name+'-'+profile, None,
                native_nodes(title, labels), typed_goal=goal,
                device_context=DeviceContext(platform='android', foreground_app='com.android.settings'),
                screen_bounds=(0, 0, 1080, 2400), observation_id='synthetic', captured_at_ms=1000,
                observation_profile=PROFILE if profile == 'common' else None))
            diagnostic = result.debug.get('ui_selection', {})
            case[profile] = {'status': diagnostic.get('status'), 'provider_calls': len(calls),
                'payload_bytes': calls[0]['payload_bytes'] if calls else 0,
                'questions': calls[0]['questions'] if calls else 0,
                'candidate_count': diagnostic.get('candidate_count', 0),
                'latency_ms': round((monotonic()-started)*1000, 2),
                'selected_menu': result.guide_command.target_node_id,
                'matches_expected': result.guide_command.target_node_id == f'row{expected}' if args.live else None}
        report['cases'].append(case)
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        with args.output.open('x', encoding='utf-8') as handle:
            handle.write(encoded)
    print(encoded, end='')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
