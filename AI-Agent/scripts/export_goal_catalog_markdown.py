"""Render the shared JSON goal catalog as a reviewer-friendly Markdown table."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path


AI_AGENT = Path(__file__).resolve().parents[1]
REPOSITORY = AI_AGENT.parent
CATALOG = AI_AGENT / "goal_catalog" / "mvp_goals.json"
OUTPUT = REPOSITORY / "docs" / "goal-type-keyword-catalog.md"


def escape(value: object) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ")


def render(catalog: dict) -> str:
    goals = catalog["goals"]
    apps = Counter(goal["target_app"] for goal in goals)
    statuses = Counter(goal["execution_status"] for goal in goals)
    modes = Counter(g["task_type"] for g in goals if g["execution_status"] == "approved")
    commerce = json.loads((AI_AGENT / 'goal_catalog/commerce_execution.json').read_text(encoding='utf-8'))['tasks']
    commerce_modes = Counter(spec.get('mode', 'assisted') for spec in commerce.values())
    lines = [
        "# Goal Type·키워드 전체 표",
        "",
        "> 이 문서는 `AI-Agent/goal_catalog/mvp_goals.json`에서 자동 생성한다. 직접 수정하지 말고 JSON 수정 후 `cd AI-Agent && python scripts/export_goal_catalog_markdown.py`를 실행한다.",
        "",
        f"- Catalog version: `{catalog['version']}`",
        f"- 전체 Goal: **{len(goals)}개** (Android 설정 {apps['android_settings']}개, 쿠팡 {apps['coupang']}개)",
        f"- 실행 승인: **{statuses['approved']}개**, 분류 전용: **{statuses['catalog_only']}개**",
        "- `키워드 그룹`의 각 괄호에서는 하나 이상, 괄호와 괄호 사이는 모두 충족해야 분류 후보가 된다.",
        "- 등록된 `utterances` 예문과 전체 문장이 일치하면 키워드 그룹이 빠져 있어도 후보로 인식한다. 띄어쓰기와 문장부호는 무시한다.",
        "- `catalog_only`는 Goal 추출만 가능하다는 뜻이다. 승인 Task Graph가 없으므로 실제 작업은 시작하지 않는다.",
        f"- 설정 변경 상태 검증 경로: **{modes['settings_change']}개**, 직접 진행 안내: **{modes['settings_assisted']}개**. 승인 표시는 실행 안내를 시작할 수 있다는 뜻이며 실기기 검증 완료를 뜻하지 않는다.",
        "- `settings_assisted`는 화면 안내 뒤 사용자가 직접 선택·입력한다. 사용자 완료 응답은 기술 검증과 별도 기록한다.",
        f"- 쿠팡: 화면 이동 **{commerce_modes['navigation']}개**, 확인 후 조작·반영 검사 **{commerce_modes['action']}개**, 직접 진행 안내 **{commerce_modes['assisted']}개**. 실기기 검증은 별도이며 로그인·개인정보 입력·거래 확정은 사용자가 진행한다.",
        "- 이전 v2.0은 82개였고, v2.1은 160개였고, v2.2는 설정 화면 열기 경로를 추가했다.",
        "",
    ]
    number = 0
    for target_app, title in (("android_settings", "Android 설정"), ("coupang", "쿠팡")):
        lines.extend([
            f"## {title}",
            "",
            "| # | Goal Type | 표시 이름 | 키워드 그룹 | 실행 상태 | 위험도 |",
            "| ---: | --- | --- | --- | --- | --- |",
        ])
        for goal in goals:
            if goal["target_app"] != target_app:
                continue
            number += 1
            groups = " + ".join(
                "(" + " / ".join(escape(keyword) for keyword in group) + ")"
                for group in goal["keyword_groups"]
            )
            status = escape(goal["execution_status"])
            if goal["task_type"] == "settings_assisted":
                status += " · 직접 진행"
            if goal['target_app'] == 'coupang':
                mode = commerce[goal['goal_type'].removeprefix('coupang.')].get('mode', 'assisted')
                status += ' · ' + {'navigation': '화면 이동', 'action': '확인 후 조작', 'assisted': '직접 진행'}[mode]
            if goal.get("graph_id"):
                status += f" (`{escape(goal['graph_id'])}`)"
            lines.append(
                f"| {number} | `{escape(goal['goal_type'])}` | {escape(goal['display_name'])} | "
                f"{groups} | {status} | {escape(goal.get('risk_level', 'normal'))} |"
            )
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    expected = render(catalog)
    if args.check:
        return 0 if OUTPUT.exists() and OUTPUT.read_text(encoding="utf-8") == expected else 1
    OUTPUT.write_text(expected, encoding="utf-8", newline="\n")
    print(f"Exported {len(catalog['goals'])} goals to {OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
