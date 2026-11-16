"""Check Coupang guidance with invented native menus; never access a device or commerce account."""
import argparse
import json
from pathlib import Path
import sys

BACKEND = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(BACKEND), str(BACKEND.parent / 'AI-Agent/src')]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path)
    parser.add_argument('--live', action='store_true', help='Use Jev for three synthetic screen selections')
    args = parser.parse_args(argv)
    if args.env_file:
        from dotenv import load_dotenv
        if not args.env_file.is_file():
            print(json.dumps({'status': 'CONFIG_ERROR', 'reason': 'missing_env_file'}))
            return 2
        load_dotenv(args.env_file, override=False)
    from app.services.screen_jev import JevScreenResolver
    from app.task_graphs import approved_graphs
    from ai_agent.runtime import ClickAgentEngine
    from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext, TypedGoal
    resolver = JevScreenResolver() if args.live else None
    if resolver:
        try:
            resolver.configuration()
        except ValueError:
            print(json.dumps({'status': 'CONFIG_ERROR', 'reason': 'invalid_jev_configuration'}))
            return 2
    engine = ClickAgentEngine(task_graphs=approved_graphs(), screen_resolver=resolver)
    def send(suffix, labels, at, *, confirmation=None):
        definition = engine.task_graphs['coupang.' + suffix].definition
        goal = TypedGoal(definition.goal_type, 'coupang', definition.task_type,
            definition.required_slots, '', 1, definition.task_id, is_supported=True, required_slots_filled=True)
        nodes = tuple(AccessibilityNode(f'control-{i}', package_name='com.coupang.mobile', text=label,
            clickable=clickable, window_id=1, bounds_in_screen=(20, 40 + i * 200, 1000, 160 + i * 200))
            for i, (label, clickable) in enumerate(labels))
        return engine.handle_turn(AgentTurnRequest('synthetic-' + suffix + str(at), 'synthetic-' + suffix,
            None, nodes, typed_goal=goal, device_context=DeviceContext('android', foreground_app='com.coupang.mobile'),
            screen_bounds=(0, 0, 1080, 2400), active_window_id=1, observation_id='synthetic-' + str(at),
            captured_at_ms=at, user_confirmation=confirmation))
    rows = []
    def record(case, result, expected_node=None, expected_command=None):
        selection = result.debug['ui_selection']
        passed = (result.guide_command.target_node_id == expected_node
            and (expected_command is None or result.guide_command.command_type.value == expected_command))
        rows.append({'case': case, 'passed': passed, 'command': result.guide_command.command_type.value,
            'target': result.guide_command.target_node_id, 'provider_called': selection.get('called', False),
            'provider_status': selection.get('status'), 'confidence': selection.get('confidence')})
    record('orders_from_home', send('order.list', [('쿠팡', False), ('장바구니', True), ('마이쿠팡', True)], 1000), 'control-2')
    record('orders_from_my_page', send('order.list', [('마이쿠팡', False), ('주문목록', True), ('쿠폰함', True)], 2000), 'control-1')
    record('orders_page_arrival', send('order.list', [('주문목록', False)], 3000), expected_command='done')
    product = [('상품 상세', False), ('장바구니 담기', True)]
    record('cart_add_confirmation', send('cart.add', product, 1000), 'control-1', 'confirmation')
    record('cart_add_after_confirmation', send('cart.add', product, 1100, confirmation='accepted'), 'control-1', 'highlight')
    duplicate = send('cart.view', [('장바구니', True), ('장바구니', True)], 1000)
    record('duplicate_target_abstention', duplicate, expected_command='highlight')
    passed = all(row['passed'] for row in rows)
    print(json.dumps({'status': 'PASS' if passed else 'FAIL', 'provider': 'jev' if args.live else 'rules',
        'source_kind': 'synthetic', 'device_execution': 'NOT_RUN',
        'actual_provider_calls': sum(row['provider_called'] for row in rows), 'cases': rows}, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
