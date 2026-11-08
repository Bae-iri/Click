"""Backend-authored navigation drafts, awaiting device selectors and verifiers."""
import json
from pathlib import Path

from ai_agent.runtime.types import (
    TaskDefinition, TaskGraph, TaskState, Transition, TargetSelector,
    ActionType, SafetyLevel,
)

SETTINGS = ("com.android.settings",)
CATEGORY_ALIASES = {
    "Connections": ("연결",),
    "Network & internet": ("네트워크 및 인터넷",),
    "Display": ("디스플레이", "화면"),
    "Sound & vibration": ("소리 및 진동",),
    "Notifications": ("알림",),
    "Location": ("위치",),
    "Battery": ("배터리",),
    "Security": ("보안",),
    "Security & privacy": ("보안 및 개인정보 보호",),
    "Apps": ("애플리케이션", "앱"),
    "Storage": ("저장 공간",),
    "Accessibility": ("접근성",),
    "System": ("시스템",),
    "General management": ("일반", "일반 관리"),
    "Software update": ("소프트웨어 업데이트",),
    "Wallpaper & style": ("배경화면 및 스타일",),
    "Wallpaper": ("배경화면",),
    "About phone": ("휴대전화 정보", "휴대폰 정보"),
}


def draft_releases():
    specifications = json.loads((Path(__file__).parent / "drafts.json").read_text(encoding="utf-8"))
    for spec in specifications:
        from ai_agent.runtime.settings_controls import DOCUMENT
        if spec['goal_type'] in DOCUMENT['goals']:
            continue  # Superseded by explicit switch or assisted releases with separate result semantics.
        task_id = spec["task_id"]
        home, category, control, changed, done = (
            f"{task_id}:{name}" for name in ("home", "category", "control", "changed", "done"))
        labels = tuple(spec["control_labels"])
        categories = tuple(dict.fromkeys(label for name in spec["category_labels"]
            for label in (*CATEGORY_ALIASES.get(name, ()), name)))
        toggle = "state" in spec["slots"]
        value = "direction" in spec["slots"]
        role = "toggle" if toggle else "slider" if value else "menu_item"
        states = {
            "S0_UNKNOWN": TaskState("S0_UNKNOWN", "알 수 없는 화면", confidence_threshold=0),
            home: TaskState(home, "설정 홈", required_keyword_groups=(("설정", "Settings"),),
                package_candidates=SETTINGS, role_hints=("menu_item",), confidence_threshold=0.8),
            category: TaskState(category, categories[0], required_keyword_groups=(categories,),
                package_candidates=SETTINGS, role_hints=("menu_item",), confidence_threshold=0.8),
            control: TaskState(control, spec["name"], required_keyword_groups=(labels,),
                optional_keywords=labels, package_candidates=SETTINGS,
                role_hints=(role,), confidence_threshold=0.8),
            changed: TaskState(changed, "결과 검증 대기", verification_only=True),
            done: TaskState(done, "완료", verification_only=True),
        }
        transitions = (
            Transition(f"{task_id}:open_settings", "S0_UNKNOWN", home, ActionType.TAP,
                "설정 앱 열기", ("설정", "Settings"), "app_icon", SafetyLevel.LOW_RISK,
                "설정 앱을 열어주세요.", selector=TargetSelector(package_candidates=(
                    "com.android.launcher3", "com.google.android.apps.nexuslauncher",
                    "com.sec.android.app.launcher"))),
            Transition(f"{task_id}:open_category", home, category, ActionType.TAP,
                "설정 분류 열기", categories, "menu_item", SafetyLevel.LOW_RISK,
                f"{categories[0]} 항목을 찾아 눌러주세요.",
                selector=TargetSelector(package_candidates=SETTINGS)),
            Transition(f"{task_id}:open_control", category, control, ActionType.TAP,
                "설정 항목 열기", labels, "menu_item", SafetyLevel.LOW_RISK,
                f"{labels[0]} 항목을 찾아 눌러주세요.",
                selector=TargetSelector(package_candidates=SETTINGS)),
            Transition(f"{task_id}:apply", control, changed,
                ActionType.TAP if toggle else ActionType.DRAG if value else ActionType.OBSERVE_ONLY,
                spec["name"], labels, role if toggle or value else None,
                SafetyLevel.CONFIRM_REQUIRED,
                f"{spec['name']} 단계를 직접 진행해 주세요. 변경 후 화면을 확인할게요.",
                requires_user_action=toggle or value,
                confirmation_message=f"{spec['name']} 작업을 진행할까요?",
                selector=TargetSelector(package_candidates=SETTINGS, context_keywords=labels),
                verification_predicate=spec["completion_predicate"], completes_task=True),
            Transition(f"{task_id}:verified", changed, done, ActionType.OBSERVE_ONLY,
                "검증된 결과 표시", (), None, SafetyLevel.LOW_RISK,
                "작업 결과가 확인되었어요.", requires_user_action=False),
        )
        graph = TaskGraph(TaskDefinition(task_id, spec["goal_type"], spec["name"],
            "android_settings", "settings_change", ("S0_UNKNOWN", home), done, spec["slots"]),
            states, transitions, {})
        yield {"version": "0.1.0-draft", "approved": False, "priority": 0,
            "status": "needs_device_evidence", "completion_predicate": spec["completion_predicate"]}, graph
