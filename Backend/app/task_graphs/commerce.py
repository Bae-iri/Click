"""Authored, versioned Coupang guidance graphs; device validation is separate."""
from ai_agent.task_graph.commerce import DOCUMENT
from ai_agent.runtime.types import (
    ActionType, SafetyLevel, TaskDefinition, TaskGraph, TaskState, TargetSelector, Transition,
)


def commerce_releases(catalog):
    tasks = DOCUMENT['tasks']
    goals = [g for g in catalog['goals'] if g['target_app'] == 'coupang']
    if {g['goal_type'].removeprefix('coupang.') for g in goals} != set(tasks):
        raise ValueError('Coupang execution contract must cover the entire catalog')
    for goal in goals:
        spec = tasks[goal['goal_type'].removeprefix('coupang.')]
        page = DOCUMENT['pages'][spec['page']]
        mode = spec.get('mode', 'assisted')
        titles = tuple(page['titles'])
        route = tuple(dict.fromkeys((*page['route'], *titles)))
        instruction = spec.get('instruction', page['steps'])
        states = {
            'S0_UNKNOWN': TaskState('S0_UNKNOWN', '쿠팡 열기', confidence_threshold=0),
            'C_NAVIGATE': TaskState('C_NAVIGATE', '쿠팡에서 ' + page['name'] + ' 찾기'),
            'C_TARGET': TaskState('C_TARGET', page['name'], required_keyword_groups=(titles,),
                package_candidates=tuple(DOCUMENT['packages'])),
            'C_VERIFY': TaskState('C_VERIFY', '조작 결과 확인', verification_only=True),
            'C_DONE': TaskState('C_DONE', '안내 결과 확인', verification_only=True),
        }
        navigation = Transition('CP_NAVIGATE', 'C_NAVIGATE', 'C_TARGET', ActionType.TAP,
            page['name'] + ' 찾기', route, 'menu_item', SafetyLevel.LOW_RISK,
            page['name'] + ' 화면으로 이동해 주세요.', allow_scroll=True,
            selector=TargetSelector(package_candidates=tuple(DOCUMENT['packages'])))
        edges = [Transition('CP_OPEN_APP', 'S0_UNKNOWN', 'C_NAVIGATE', ActionType.OBSERVE_ONLY,
            '쿠팡 열기', (), None, SafetyLevel.LOW_RISK, '쿠팡 앱을 열어주세요.', requires_user_action=False), navigation]
        if mode == 'action':
            edges.append(Transition('CP_ACTION', 'C_TARGET', 'C_VERIFY', ActionType.TAP,
                goal['display_name'], tuple(spec['action_labels']), 'button', SafetyLevel.CONFIRM_REQUIRED,
                instruction, confirmation_message=instruction + ' 표시한 버튼으로 진행할까요?',
                selector=TargetSelector(package_candidates=tuple(DOCUMENT['packages'])),
                verification_predicate='commerce_effect_observed'))
            edges.append(Transition('CP_VERIFIED', 'C_VERIFY', 'C_DONE', ActionType.OBSERVE_ONLY,
                '결과 확인', (), None, SafetyLevel.LOW_RISK, '안내한 조작의 반영을 확인했어요.',
                requires_user_action=False, verification_predicate='commerce_effect_observed', completes_task=True))
        else:
            edges.append(Transition('CP_RESULT', 'C_TARGET', 'C_DONE', ActionType.OBSERVE_ONLY,
                '화면 확인' if mode == 'navigation' else '직접 진행 안내', (), None, SafetyLevel.LOW_RISK,
                page['name'] + ' 화면을 열었어요.' if mode == 'navigation' else instruction,
                requires_user_action=False, completes_task=True,
                verification_predicate='commerce_page_visible' if mode == 'navigation' else 'commerce_manual_result_required'))
        synonyms = {'commerce_mode': (mode,), 'commerce_instructions': (page['steps'], instruction),
            'commerce_navigation': route, 'commerce_success': tuple(spec.get('success_labels', ())),
            **{f'commerce_controls_{i}': tuple(group) for i, group in enumerate(page.get('controls', ()))}}
        graph = TaskGraph(TaskDefinition(goal['graph_id'], goal['goal_type'], goal['display_name'],
            'coupang', goal['task_type'], ('S0_UNKNOWN', 'C_NAVIGATE', 'C_TARGET'), 'C_DONE', goal['slots']),
            states, tuple(edges), synonyms)
        yield {'version': '1.0.0', 'approved': True, 'priority': 100,
            'status': mode + '_device_validation_pending'}, graph
