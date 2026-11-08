"""Read-only Android settings routes. Publication is not a device compatibility claim."""
import json
from pathlib import Path
from ai_agent.runtime.types import TaskDefinition, TaskGraph, TaskState, Transition, TargetSelector, ActionType, SafetyLevel

PAGES = json.loads((Path(__file__).parent / 'navigation_pages.json').read_text(encoding='utf-8'))
SETTINGS = ('com.android.settings', 'com.samsung.android.settings')
LAUNCHERS = ('com.android.launcher3', 'com.google.android.apps.nexuslauncher', 'com.sec.android.app.launcher',
    'com.miui.home', 'com.huawei.android.launcher', 'com.oppo.launcher', 'com.vivo.launcher', 'org.lineageos.trebuchet')


def navigation_releases():
    for page in PAGES:
        key = page['key']
        task_id = 'NAV-' + key.replace('_', '-')
        goal_type = 'settings.navigation.' + key
        def state(id, name, labels=(), terminal=False):
            return TaskState(id, name, required_keyword_groups=(tuple(labels),) if labels else (),
                optional_keywords=tuple(labels), package_candidates=SETTINGS if labels else (),
                role_hints=('text', 'menu_item', 'list'), confidence_threshold=0.7 if labels else 0,
                verification_only=terminal)
        states = {'S0_UNKNOWN': state('S0_UNKNOWN', '알 수 없는 화면'),
            'S1_SETTINGS_HOME': state('S1_SETTINGS_HOME', '설정', ('설정', 'Settings')),
            'S_TARGET': state('S_TARGET', page['name'], page['titles']),
            'S_DONE': state('S_DONE', '설정 화면 도착', terminal=True)}
        edges = [Transition('S0_TO_S1_OPEN_SETTINGS', 'S0_UNKNOWN', 'S1_SETTINGS_HOME', ActionType.TAP,
            '설정 앱 열기', ('설정', 'Settings'), 'app_icon', SafetyLevel.LOW_RISK, '설정 앱을 열어주세요.',
            selector=TargetSelector(package_candidates=LAUNCHERS))]
        def edge(id, start, end, labels, name, priority=0):
            edges.append(Transition(id, start, end, ActionType.TAP, name + ' 열기', tuple(labels),
                'menu_item', SafetyLevel.LOW_RISK, name + ' 항목을 눌러주세요.', allow_scroll=True,
                selector=TargetSelector(package_candidates=SETTINGS), priority=priority))
        if page['categories']:
            states['S_CATEGORY'] = state('S_CATEGORY', page['categories'][0], page['categories'])
            edge('OPEN_CATEGORY', 'S1_SETTINGS_HOME', 'S_CATEGORY', page['categories'], page['categories'][0], 0)
            edge('OPEN_PAGE', 'S_CATEGORY', 'S_TARGET', page['titles'], page['name'])
            if page['intermediate']:
                states['S_INTERMEDIATE'] = state('S_INTERMEDIATE', page['intermediate'][0], page['intermediate'])
                edge('OPEN_INTERMEDIATE', 'S_CATEGORY', 'S_INTERMEDIATE', page['intermediate'], page['intermediate'][0], 1)
                edge('OPEN_PAGE_FROM_INTERMEDIATE', 'S_INTERMEDIATE', 'S_TARGET', page['titles'], page['name'])
        if key == 'settings':
            edges.append(Transition('SETTINGS_HOME_REACHED', 'S1_SETTINGS_HOME', 'S_TARGET', ActionType.OBSERVE_ONLY,
                '설정 화면 확인', (), None, SafetyLevel.LOW_RISK, '설정 화면을 확인할게요.', requires_user_action=False))
        if key != 'settings':
            edge('OPEN_PAGE_FROM_HOME', 'S1_SETTINGS_HOME', 'S_TARGET', page['titles'], page['name'], 10)
        edges.append(Transition('PAGE_VISIBLE', 'S_TARGET', 'S_DONE', ActionType.OBSERVE_ONLY,
            page['name'] + ' 화면 도착 확인', (), None, SafetyLevel.LOW_RISK,
            page['name'] + ' 설정 화면을 열었어요.', requires_user_action=False,
            verification_predicate='settings_page_visible', completes_task=True))
        graph = TaskGraph(TaskDefinition(task_id, goal_type, page['name'] + ' 설정 열기', 'android_settings',
            'settings_navigation', ('S0_UNKNOWN', 'S1_SETTINGS_HOME'), 'S_DONE',
            {'setting': 'navigation_' + key, 'action': 'open'}), states, tuple(edges), {})
        yield {'version': '1.0.0', 'approved': True, 'priority': 100,
            'status': 'read_only_navigation_device_validation_pending'}, graph
