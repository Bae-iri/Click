# Backend·AI2 Goal 확장 점검 — 2026-11-09

`develop`을 `3525b1e`에서 `44de4af`로 fast-forward pull했다. 기존 테스트 2개의 UTF-8 읽기 수정은 보존했다. 이번 변경은 Backend의 Jev 목표 해석 설명, AI2의 카탈로그 감사, 관련 회귀 검사와 현재 지원 범위 문서다.

## 지원 범위와 Backend 확인

카탈로그 v2.3.0의 181개 중 Android 설정 110개가 실행 승인 상태이며 쿠팡 71개는 분류 전용이다. 설정 변경 검증 50개, 화면 이동 21개, 직접 진행 안내 39개를 구분한다. 직접 진행 안내의 사용자 만족 응답을 기술 검증 성공으로 승격하지 않는다. 전 목표의 OEM 실기기 완료를 검증한 결과는 아니다.

일회용 SQLite DB에서 현재 migration head까지 적용하고 서버 시작 시드를 두 번 실행했다. 중복 없이 110개가 등록됐고 모두 카탈로그의 슬롯·작업 종류와 일치하는 graph를 선택했다. 현재 API 33개 경로는 저장된 OpenAPI와 일치한다. 이번 변경에 새 DB migration은 없다.

## 수정한 문제

기존 `agent2.py audit`는 DB에 존재하는 graph만 검사했다. 따라서 특정 목표의 graph가 삭제·비활성화되어도 나머지 graph가 유효하면 CLI가 성공 종료할 수 있었다. 이제 카탈로그의 모든 승인 목표를 실제 Backend 선택기로 조회한다.

- `MISSING_GRAPH`: 누락·비활성·승인 해제로 선택 불가.
- `AMBIGUOUS_GRAPH`: 최상위 우선순위가 같은 release가 여러 개.
- `INVALID_GRAPH`: 본문·hash·schema 등의 실행 검증 실패.
- `GRAPH_CONTRACT_MISMATCH`: 카탈로그의 슬롯·작업 종류와 불일치.
- 승인 목표 밖의 활성·승인 graph는 `unexpected_executable_goals`로 보고.

목표별 선택 version/hash와 작업 종류별 수를 보고하며 여러 release를 목표 하나로 센다. 누락이나 결함은 `status=BLOCKED`, 종료 코드 1이다. 감사는 DB를 복구·수정하지 않는다.

실제 Jev 연결 검사에서는 기존 설명으로 고정 합성 문장 8개 중 4개만 기대한 결과로 연결됐다. 실패 4개를 별도로 조회하니 모두 HTTP 200이었으며 미디어 요청의 미지원 판단 또는 음량·테마 요청의 기준 미달이었다. 앱 이름이 나온 미디어 요청과 앱 내부 전용 조절, 테마와 화면 밝기의 차이를 분류 설명에 추가했다. 같은 8개 문장은 수정 후 모두 통과했다. `jev-1.14.0`, 기준값 0.8, 응답 검증과 보류 동작은 유지했다. 고정된 소수 사례의 연결 검사이며 전체 모델 정확도 개선 수치는 아니다.

## 검증

| 검사 | 결과 |
| --- | --- |
| pull 직후 Backend 전체 회귀 | 2,014개 통과 |
| 최종 목표 해석·설정 요청·입력 확인·검증 환경·AI2 회귀 | 278개 통과 |
| AI-Agent 전체 회귀 | 410개 통과 |
| migration 왕복·schema check·실제 로컬 HTTP·재시작·재전송·복구·취소 | 통과 |
| migration DB의 seed 중복 방지·실제 CLI 감사 | 승인·선택 가능 110개, 차단 0개 |
| 현재 OpenAPI 재생성 | 33개 경로, 기존 명세와 차이 없음 |
| 실제 Jev의 고정 합성 요청 흐름 | 수정 전 4/8, 수정 후 8/8 |
| AI2 합성 CLI pipeline | episode 4개·후보 1개, 독립 근거가 없어 `NEEDS_DATA` |

전체 회귀는 pull 직후 코드로 시작한 `tests.xml`의 2,014개 통과 결과다. 그 이후 수정한 최종 코드에 대한 회귀는 `final-affected-tests.xml`의 278개 통과 결과다. 두 검사 수는 중복을 포함하므로 합산하지 않는다. 기존 Starlette/Alembic deprecation warning 14개는 남아 있다.

최종 실행 결과와 로그는 로컬 `C:/click_server/handoff/goal-rollout-2026-11-09/`에 보관한다. `graph-audit.json`은 일회용 migration DB의 110개 목표 선택 결과, `live-diagnostics.json`은 수정 전 고정 합성 요청의 상태·점수, `live-goal-flow-after.log`는 수정 후 8/8 결과다. 운영 사용자 원문이나 키는 기록하지 않는다.

## 운영 반영

2026-11-09 13:22 KST 공개 서버 조회에서 `/health`, `/ready`는 200, DB는 정상, `/openapi.json`은 로컬과 일치했다. 요청 해석과 화면 선택은 Jev로 구성되어 있었다. `/version.source_revision`은 아직 `3525b1e`였다. 이 revision에도 설정 110개 확장 코드는 포함돼 있지만 오늘 pull한 공통 설정 앱 진입 복구 `44de4af`와 이번 로컬 수정은 포함되지 않았다. 같은 시각 `git ls-remote`로 조회한 원격 develop HEAD는 로컬과 동일한 `44de4af`였다.

이번 소스 변경은 운영 배포 대상에 포함한다. 실제 Render 재배포는 사용자가 진행한다. 연결된 Render 관리 도구나 로컬 deploy hook/API key가 없어 운영 배포는 직접 실행하지 않았다. 운영 DB의 graph 행과 실행 로그, GitHub CI 상태도 조회하지 않았다.

반영 순서는 이번 변경의 커밋·푸시 → Render의 해당 커밋 배포 → `/version` 확인 → 서버 환경에서 아래 두 검사를 실행하는 순서다. 기존 암호화 키·DB·Jev 설정을 유지한다.

```sh
python scripts/agent2.py audit
python scripts/check_natural_goal_flow.py --run-live
```

감사의 기대값은 `status=PASS`, `catalog_coverage.selectable_goals=110`, `blocked_goals=[]`, `unexpected_executable_goals=[]`다. 모델 검사는 개인정보 없는 고정 합성 문장만 전송한다. 실기기 설치·조작·완료와 운영 DB의 실제 감사는 별도 확인이 필요하다.
