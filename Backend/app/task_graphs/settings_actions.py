"""Confirmed switch routes and explicit user-operated settings handoffs.

These releases publish a contract; physical OEM validation remains pending.
An assisted graph cannot mutate a setting or complete by page visibility.
"""
import json
from pathlib import Path
from ai_agent.runtime.settings_controls import DOCUMENT
from ai_agent.runtime.types import TaskDefinition, TaskGraph, TaskState, Transition, TargetSelector, ActionType, SafetyLevel
from .settings_navigation import SETTINGS, LAUNCHERS
from ai_agent.task_graph.settings import CHOICES, choice_spec, choice_predicate


def action_releases():
    specs = {s['goal_type']: s for s in json.loads((Path(__file__).parent / 'drafts.json').read_text(encoding='utf-8'))}
    for goal_type, capability in DOCUMENT['goals'].items():
        mode = capability['mode']
        if mode not in {'native_checked', 'assisted', 'system_choice', 'native_slider'}:
            continue
        spec = specs[goal_type]
        setting = capability['setting']
        contract = (DOCUMENT['toggles'][setting] if mode == 'native_checked' else CHOICES[setting]
                    if mode == 'system_choice' else capability)
        pages, categories = contract['pages'], contract['categories']
        labels = contract['labels'] if mode == 'native_checked' else pages
        intermediate = contract.get('intermediate', [])
        def state(key, name, words=(), terminal=False):
            return TaskState(key, name, required_keyword_groups=(tuple(words),) if words else (),
                optional_keywords=tuple(words), package_candidates=SETTINGS if words else (),
                role_hints=('text', 'menu_item', 'toggle'), confidence_threshold=.7 if words else 0,
                verification_only=terminal)
        states = {'S0_UNKNOWN': state('S0_UNKNOWN', '알 수 없는 화면'),
            'S1_SETTINGS_HOME': state('S1_SETTINGS_HOME', '설정', ('설정', 'Settings')),
            'S_TARGET': state('S_TARGET', capability['name'], pages),
            'S_CHANGED': state('S_CHANGED', '검증 대기', terminal=True),
            'S_DONE': state('S_DONE', '결과', terminal=True)}
        edges = [Transition('S0_TO_S1_OPEN_SETTINGS', 'S0_UNKNOWN', 'S1_SETTINGS_HOME', ActionType.TAP,
            '설정 앱 열기', ('설정', 'Settings'), 'app_icon', SafetyLevel.LOW_RISK, '설정 앱을 열어주세요.',
            selector=TargetSelector(package_candidates=LAUNCHERS))]
        def edge(key, start, end, words, priority=0):
            edges.append(Transition(key, start, end, ActionType.TAP, words[0] + ' 열기', tuple(words),
                'menu_item', SafetyLevel.LOW_RISK, words[0] + ' 항목을 눌러주세요.', allow_scroll=True,
                selector=TargetSelector(package_candidates=SETTINGS), priority=priority))
        if categories:
            states['S_CATEGORY'] = state('S_CATEGORY', categories[0], categories)
            edge('OPEN_CATEGORY', 'S1_SETTINGS_HOME', 'S_CATEGORY', categories)
            edge('OPEN_PAGE', 'S_CATEGORY', 'S_TARGET', pages)
            path = contract.get('intermediate_path', [intermediate] if intermediate else [])
            previous = 'S_CATEGORY'
            ancestors = []
            for index, words in enumerate(path):
                key = 'S_INTERMEDIATE' if index == 0 else f'S_INTERMEDIATE_{index + 1}'
                suffix = '' if index == 0 else f'_{index + 1}'
                states[key] = state(key, words[0], words)
                edge('OPEN_INTERMEDIATE' + suffix, previous, key, words, 1)
                if contract.get('allow_intermediate_shortcuts'):
                    for ancestor in ancestors:
                        edge('OPEN_INTERMEDIATE' + suffix + '_FROM_' + ancestor, ancestor, key, words, 1)
                edge('OPEN_PAGE_FROM_INTERMEDIATE' + suffix, key, 'S_TARGET', pages)
                ancestors.append(previous)
                previous = key
        edge('OPEN_PAGE_FROM_HOME', 'S1_SETTINGS_HOME', 'S_TARGET', pages, 10)
        if mode == 'native_checked':
            # Some devices place this switch directly on the category page.
            # Grounding still requires this task's exact label and independent heading.
            states['S_TARGET'] = state('S_TARGET', capability['name'], pages)
            edges.append(Transition('APPLY_SETTING', 'S_TARGET', 'S_CHANGED', ActionType.TAP,
                capability['name'], tuple(labels), 'toggle', SafetyLevel.CONFIRM_REQUIRED,
                f"표시한 스위치로 {capability['name']}를 진행해 주세요.",
                confirmation_message=f"{capability['name']}를 진행할까요?",
                selector=TargetSelector(package_candidates=SETTINGS, context_keywords=tuple(pages), allow_unlabelled_in_context=True),
                verification_predicate='target_checked_' + spec['slots']['state'], completes_task=True))
            choice = contract.get('choices', {}).get(spec['slots']['state'])
            if choice:
                edges.append(Transition('APPLY_MODE_CHOICE', 'S_TARGET', 'S_CHANGED', ActionType.TAP,
                    capability['name'], tuple(choice['labels']), 'menu_item', SafetyLevel.CONFIRM_REQUIRED,
                    f"표시한 {choice['labels'][0]} 항목을 눌러주세요.",
                    confirmation_message=f"{capability['name']}를 진행할까요?",
                    selector=TargetSelector(package_candidates=SETTINGS),
                    verification_predicate='system_night_mode_' + spec['slots']['state'], completes_task=True))
            edges.append(Transition('VERIFIED', 'S_CHANGED', 'S_DONE', ActionType.OBSERVE_ONLY,
                '결과 확인', (), None, SafetyLevel.LOW_RISK, '스위치 상태를 확인했어요.', requires_user_action=False))
        elif mode == 'native_slider':
            direction = spec['slots']['direction']
            verb, movement = ('크게', '오른쪽') if direction == 'increase' else ('작게', '왼쪽')
            edges.append(Transition('ADJUST_DISPLAY_SIZE', 'S_TARGET', 'S_CHANGED', ActionType.DRAG,
                capability['name'], tuple(contract['labels']), 'slider', SafetyLevel.CONFIRM_REQUIRED,
                f'화면 크기 조절 막대를 {movement}으로 조금 움직여주세요.',
                confirmation_message=f'화면 요소를 {verb} 조절할까요?',
                selector=TargetSelector(package_candidates=SETTINGS, resource_ids=tuple(contract['resource_ids']),
                    context_keywords=tuple(pages), excluded_keywords=('글자 크기','Font size','밝기','Brightness'),
                    allow_unlabelled_in_context=True),
                verification_predicate='target_value_' + ('increased' if direction == 'increase' else 'decreased'),
                completes_task=True))
            edges.append(Transition('VERIFIED', 'S_CHANGED', 'S_DONE', ActionType.OBSERVE_ONLY,
                '화면 크기 적용 확인', (), None, SafetyLevel.LOW_RISK, '화면 크기 변경을 확인했어요.', requires_user_action=False))
        elif mode == 'system_choice':
            choice = choice_spec(spec['slots'])
            edges.append(Transition('APPLY_MODE_CHOICE', 'S_TARGET', 'S_CHANGED', ActionType.TAP,
                capability['name'], tuple(choice['labels']), 'menu_item', SafetyLevel.CONFIRM_REQUIRED,
                f"표시한 {choice['labels'][0]} 항목을 눌러주세요.",
                confirmation_message=f"{capability['name']}를 진행할까요?",
                selector=TargetSelector(package_candidates=SETTINGS),
                verification_predicate=choice_predicate(spec['slots']), completes_task=True))
            edges.append(Transition('VERIFIED', 'S_CHANGED', 'S_DONE', ActionType.OBSERVE_ONLY,
                '결과 확인', (), None, SafetyLevel.LOW_RISK, '선택한 모드의 적용 상태를 확인했어요.', requires_user_action=False))
        else:
            edges.append(Transition('MANUAL_HANDOFF', 'S_TARGET', 'S_DONE', ActionType.OBSERVE_ONLY,
                '직접 진행 안내', (), None, SafetyLevel.LOW_RISK,
                capability['instruction'] + ' 직접 진행한 뒤 완료 확인을 눌러주세요.',
                requires_user_action=False, verification_predicate='manual_result_required', completes_task=True))
        graph = TaskGraph(TaskDefinition(spec['task_id'], goal_type, capability['name'], 'android_settings',
            'settings_change' if mode in {'native_checked', 'system_choice', 'native_slider'} else 'settings_assisted',
            ('S0_UNKNOWN', 'S1_SETTINGS_HOME'), 'S_DONE', spec['slots']), states, tuple(edges), {})
        has_choices = mode == 'system_choice' or mode == 'native_checked' and bool(contract.get('choices'))
        yield {'version': capability.get('graph_version', '1.1.0' if has_choices else '1.0.0'),
            'approved': True, 'priority': 110 if has_choices or capability.get('graph_version') else 100,
            'status': mode + '_device_validation_pending'}, graph
