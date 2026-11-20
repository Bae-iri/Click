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
    lines = [
        "# Goal Type·키워드 전체 표",
        "",
        "> 이 문서는 `AI-Agent/goal_catalog/mvp_goals.json`에서 자동 생성한다. 직접 수정하지 말고 JSON 수정 후 `cd AI-Agent && python scripts/export_goal_catalog_markdown.py`를 실행한다.",
        "",
        f"- Catalog version: `{catalog['version']}`",
        f"- 전체 Goal: **{len(goals)}개** (Android 설정 {apps['android_settings']}개, 정부24 {apps['gov24']}개, 쿠팡 {apps.get('coupang', 0)}개)",
        f"- 그래프 실행 승인: **{statuses['approved']}개**, 공개 화면 안내 연결: **{statuses['public_guide']}개**, 분류 전용: **{statuses['catalog_only']}개**",
        "- `키워드 그룹`의 각 괄호에서는 하나 이상, 괄호와 괄호 사이는 모두 충족해야 분류 후보가 된다.",
        "- 등록된 `utterances` 예문과 전체 문장이 일치하면 키워드 그룹이 빠져 있어도 후보로 인식한다. 띄어쓰기와 문장부호는 무시한다.",
        "- `catalog_only`는 Goal 추출만 가능하다는 뜻이다. 승인 Task Graph가 없으므로 실제 작업은 시작하지 않는다.",
        "- `public_guide`는 별도 공개 화면 안내로 연결된다. 로그인 성공이나 민원 신청·발급 완료를 뜻하지 않는다.",
        f"- 설정 변경 상태 검증 경로: **{modes['settings_change']}개**, 직접 진행 안내: **{modes['settings_assisted']}개**. 승인 표시는 실행 안내를 시작할 수 있다는 뜻이며 실기기 검증 완료를 뜻하지 않는다.",
        "- `settings_assisted`는 화면 안내 뒤 사용자가 직접 선택·입력한다. 사용자 완료 응답은 기술 검증과 별도 기록한다.",
        "- v2.5.0부터 쿠팡 71개 목표는 활성 목록에서 제외했다. 기존 버전의 기록·그래프 호환용 목록은 `legacy_coupang_goals.json`에 보존한다.",
        "- v2.6.0은 쿠팡 화면 이동 목표 8개(장바구니·주문목록·찜·쿠폰함·카테고리·고객센터·최근 검색어·와우 혜택)만 보관 목록의 계약 그대로 다시 활성화했다. 주문·결제·취소 같은 나머지 63개는 계속 보관 목록에만 있다.",
        "- 정부24는 로그인 방법 선택·주민등록등본 발급 안내 화면 찾기 두 목표가 연결되어 있다. 나머지 민원은 준비 중이며 실제 화면 경로를 임의 생성하지 않는다.",
        "- 이전 v2.0은 82개였고, v2.1은 160개였고, v2.2는 설정 화면 열기 경로를 추가했다.",
        "",
    ]
    number = 0
    for target_app, title in (("android_settings", "Android 설정"), ("gov24", "정부24 생활 민원"), ("coupang", "쿠팡 화면 이동")):
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
            if goal['execution_status'] == 'public_guide':
                status += ' · 공개 화면까지만 안내'
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
