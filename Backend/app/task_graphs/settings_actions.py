"""Confirmed switch routes and explicit user-operated settings handoffs.

These releases publish a contract; physical OEM validation remains pending.
An assisted graph cannot mutate a setting or complete by page visibility.
"""
import json
from pathlib import Path
from ai_agent.runtime.settings_controls import DOCUMENT
from ai_agent.runtime.types import TaskDefinition, TaskGraph, TaskState, Transition, TargetSelector, ActionType, SafetyLevel
from .settings_navigation import SETTINGS, LAUNCHERS


def action_releases():
    specs = {s['goal_type']: s for s in json.loads((Path(__file__).parent / 'drafts.json').read_text())}
    for goal_type, capability in DOCUMENT['goals'].items():
        mode = capability['mode']
        if mode not in {'native_checked', 'assisted'}:
            continue
        spec = specs[goal_type]
        setting = capability['setting']
        contract = DOCUMENT['toggles'].get(setting) if mode == 'native_checked' else capability
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
            if intermediate:
                states['S_INTERMEDIATE'] = state('S_INTERMEDIATE', intermediate[0], intermediate)
                edge('OPEN_INTERMEDIATE', 'S_CATEGORY', 'S_INTERMEDIATE', intermediate, 1)
                edge('OPEN_PAGE_FROM_INTERMEDIATE', 'S_INTERMEDIATE', 'S_TARGET', pages)
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
        else:
            edges.append(Transition('MANUAL_HANDOFF', 'S_TARGET', 'S_DONE', ActionType.OBSERVE_ONLY,
                '직접 진행 안내', (), None, SafetyLevel.LOW_RISK,
                capability['instruction'] + ' 직접 진행한 뒤 완료 확인을 눌러주세요.',
                requires_user_action=False, verification_predicate='manual_result_required', completes_task=True))
        graph = TaskGraph(TaskDefinition(spec['task_id'], goal_type, capability['name'], 'android_settings',
            'settings_change' if mode == 'native_checked' else 'settings_assisted',
            ('S0_UNKNOWN', 'S1_SETTINGS_HOME'), 'S_DONE', spec['slots']), states, tuple(edges), {})
        has_choices = mode == 'native_checked' and bool(contract.get('choices'))
        # Route amendments need a new release without changing pinned graphs.
        yield {'version': capability.get('graph_version', '1.1.0' if has_choices else '1.0.0'),
            'approved': True, 'priority': 110 if has_choices else 100,
            'status': mode + '_device_validation_pending'}, graph
