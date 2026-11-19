# AI1 Jev 주 판단 연결

`AI1_UI_SELECTOR_PROVIDER=jev`이면 `RuntimeAgentAdapter`가 `JevScreenResolver`를
AI1의 주 판단기로 주입한다. 현재 화면 확인과 UI 대상 선택을 작은 질문으로 나누고,
코드가 답의 일관성을 검증한다.

## 공통 화면 의미로 설정 메뉴 탐색

서버 OpenAPI가 `AccessibilityTree.observation_profile=common-screen.v1`을 지원하면
새 Android 앱은 설정 화면의 제목·공개 컨트롤 제목·계층을 이 프로필로 보낸다.
등록된 목표의 어휘에 없다는 이유로 공개 메뉴 제목을 삭제하지 않는다. 본문·요약·
계정 영역은 제외하고, 입력·인증 화면 차단과 화면 안의 실제 대상 결합은 유지한다.
공개 문구의 길이와 개인정보 패턴 정책은 공용 `screen_privacy_policy.json`을 사용한다.
구조와 패턴으로 공개 문구를 구분하는 정책이며, 임의의 화면 문구가 익명임을 보장하지 않는다.

메뉴 이동은 현재 보이는 메뉴·버튼의 역할, 제목, 부모 관계와 목표로 판단한다.
알려진 페이지 목록·등록된 경로·키워드 유사도·경로 우선순위로 후보를 미리 제한하지 않는다.
현재 화면의 후보는 모두 전달하며, 범위 한도 초과를 임의 상위 후보 선택으로 숨기지 않는다.
한 Choice 요청에서 실제 메뉴 또는 `insufficient_evidence`를 선택한다.
본문 중복과 전체 작업 그래프를 보내지 않으며 실제 노드 ID와 좌표는 전송하지 않는다.

`COMMON_OPEN_MENU`는 turn 실행 중 추가하는 공통 메뉴 이동 계약이다.
등록된 그래프 본문이나 승인 hash를 변경·게시하지 않는다. 화면 제목을 모른다는 이유로
먼저 뒤로 가던 설정 경로 복구도 이 프로필에서는 생략한다. 유효한 안내는 재사용하고,
안내·대상·시간이 연결된 실제 탭 이후 의미 있는 화면 변화만 이동 결과로 센다.
좌표·생성 ID 변경은 이동 성공이나 작업 완료가 아니다.

실제 슬라이더·토글 등 설정 변경은 기존 대상 식별·사용자 승인·시스템 값 검증을 사용한다.
이번 범위는 **지원하는 설정 작업의 네이티브 메뉴 탐색**이다. 목표 카탈로그, 새 앱 실행,
정부24 전용 공개 옵션 추출, OCR 어휘와 인증·신청·결제 경계까지 확장한 변경은 아니다.
기존 프로필과 이전 서버 연결은 기존 필터를 사용한다. 적용에는 Backend 배포와 새 APK가
모두 필요하다. 진행 중 세션의 프로필은 체크포인트에 보존한다.

비교 도구는 공개 문구로 만든 합성 화면만 사용하며 기기를 조작하지 않는다.

```sh
python scripts/compare_common_screen.py --output /tmp/common-inputs.json
python scripts/compare_common_screen.py --live --env-file .env --output /tmp/common-live.json
```

기본 실행은 입력 구성만 측정한다. `--live`는 기존 키로 실제 Jev를 호출한다.
예상 메뉴와 비교하지만 소수 합성 사례의 결과를 실제 앱·기종 성공률로 일반화하지 않는다.
실제 API 비교 및 검증 범위는 `docs/reviews/2026-11-19-common-screen-semantics.json`에 기록한다.

백엔드 담당자의 기존 `TYPESAFE_API_KEY`를 사용한다. 프론트엔드 개발자에게
키를 전달할 필요는 없다. 기존 `.env`에 다음 설정을 반영한다.

```dotenv
AI1_UI_SELECTOR_PROVIDER=jev
TYPESAFE_API_KEY=<기존 값 유지>
JEV_MODEL=jev-1.14.0
JEV_MIN_CONFIDENCE=0.8
JEV_SCREEN_JUDGMENT_MODE=atomic
JEV_CHOICE_TEMPERATURE=0.7
```

`.env` 사용 시 서버 실행 명령이 파일을 읽는지 확인한다.
예: Backend 폴더에서 `python -m uvicorn app.main:app --env-file .env`.
변경된 Backend와 AI-Agent 코드를 함께 배포하고 프로세스를 재시작한다.
`GOAL_CLASSIFIER_PROVIDER`는 요청 목표 해석의 별도 설정이며 여기서 바꾸지 않는다.

## 작은 판단 세 개, HTTP 요청 한 번

기본값 `atomic`은 설정·쿠팡의 AI1 화면 판단을 다음 세 질문으로 나눈다.

| 질문 | 타입 | 판단 범위 |
| --- | --- | --- |
| `page` | Choice | 화면에 보이는 페이지가 어느 승인된 출발 단계인지. 불명확하면 `unknown_page` |
| `target_supported` | Noul | 현재 화면에 적절한 다음 행동 후보가 하나라도 존재하는지 |
| `screen` | Choice | 관측에 근거한 실제 다음 대상 또는 대기·판단 보류 |

세 질문은 같은 관측을 사용하며 서로의 답을 입력으로 받지 않는다. 추가 왕복이나
자동 재시도는 없다. 설정 입력에서는 전체 미래 그래프 대신 후보의 출발 페이지·
목적지·대상 이름을 보낸다. 이전 세션 단계로 후보를 제한하지 않아 사용자 우회나
단계 건너뛰기 후에도 현재 화면을 다시 판단할 수 있다. 쿠팡의 `C_NAVIGATE`는
특정 제목 하나가 아니라 목적 페이지에 도달하기 전의 탐색 단계로 설명한다.

행동 안내는 원본 대상 점수, 원본 페이지 점수, Noul 값이 모두 기존 기준을 통과하고,
페이지 답이 선택한 대상의 출발 단계와 일치할 때만 허용한다. 어느 답이든 누락되거나
형식이 잘못되면 전체 판단을 거절한다. 유효한 대기·판단 보류 응답은 행동을 만들지 않는다.
승인과 실제 행동 결과 검증은 별도로 유지한다. 새 보류 사유는
`target_not_supported`, `page_not_identified`, `page_confidence_below_minimum`,
`page_target_conflict`다. 잘못된 화면이라는 판단만으로 임의의 뒤로가기 동작을 만들지 않는다.

`JEV_SCREEN_JUDGMENT_MODE=legacy`는 기존 단일 질문으로 명시적으로 되돌리는 설정이다.
부분 응답을 받았다는 이유로 자동 전환하지 않는다. `/version`의
`runtime_configuration.screen_judgment_mode`와 `choice_temperature`로 실제 배포 설정을 확인한다.

모델 보류 이후 재호출 여부는 정규화한 대상의 의미·값·활성 여부·실제 가시성으로
판단한다. 위치·생성된 노드 ID·window 번호만 바뀌어도 보류가 풀리던 동작을 제거했다.
반면 이미 허용한 안내의 대상 좌표가 바뀌면 기존 안내를 그대로 재사용하지 않는다.
옛 체크포인트에 의미 기반 지문이 없으면 실제 페이지 이동이나 허용된 복구 전까지
보류를 유지한다. 동작 재시도 예산과 사용자 승인은 이 지문으로 초기화하지 않는다.
민감 화면의 `privacy_blocked` 응답은 어댑터에서도 `blocked`·`BLOCKED`를 유지한다.
일반적인 복구 안내로 변환해 차단 표시를 약화하지 않는다.

## 낮은 temperature의 적용 범위

공식 System One 요청 스키마에는 temperature 파라미터가 없다. Jev가 반환한 확률에
로컬에서 다음 식을 적용한다. 확률을 logits처럼 취급한 `softmax(p/T)`는 사용하지 않는다.

```text
q_i = softmax(log(p_i) / T) = p_i^(1/T) / sum_j p_j^(1/T)
T = 0.7  (환경변수 허용 범위: 0 < T <= 10)
```

이 변환은 1위 선택을 바꾸거나 동률을 해소하지 않는다. 낮은 T는 기존 차이를 키우며,
잘못된 선택도 더 확신하게 표현할 수 있다. 따라서 보정값은 진단·비교에만 사용하고,
통과 기준·후보 선택·승인·복구·완료 판정에는 사용하지 않는다. 원본 confidence와
확률 분포를 먼저 검증하며, 낮은 원본 점수가 T 보정으로 통과하는 일은 없다.
0인 확률은 0으로 유지하고 log-space에서 계산해 수치 오버플로를 피한다.

설계 근거: [System One 계약](https://docs.typesafe.ai/api/system-one),
[한 요청의 독립 질문](https://docs.typesafe.ai/patterns/fan-out),
[선택지 적합성 확인](https://docs.typesafe.ai/patterns/companion-noul).
질문 분리와 입력 축소만으로 실제 모델 정확도나 서버 응답 속도가 개선됐다고 단정하지 않는다.

모델·네트워크 없이 입력 크기와 로컬 준비 비용을 비교하려면 다음 명령을 사용한다.

```sh
python scripts/benchmark_jev_judgments.py --iterations 20 --output /tmp/jev-payloads.json
```

합성 설정 화면 6종에서는 입력이 약 26~37% 작아졌다. 쿠팡 탐색 1종은 기존 입력이
작아서 세 질문 추가 후 약 121% 커졌다. 두 모드 모두 관측당 요청 경계 진입은 1회다.
이 비교는 실제 API 호출·기기 조작·전체 서버 응답 지연을 측정하지 않는다.

연결 확인은 백엔드 담당자 환경에서 실행한다:

```sh
python scripts/check_jev_screen.py --env-file .env
```

`mode: primary`, `called: true`, `status: selected`와 종료 코드 0이 성공이다.
같은 진단은 API의 `metadata.debug.ui_selection`에도 남는다.
자동 테스트는 실제 API 키나 네트워크 호출을 사용하지 않는다.

`called: true`와 `status: low_confidence` 또는 `abstained`가 함께 나오면
연결 실패가 아니라 Jev가 응답했지만 선택을 보류한 상태다.
API 응답, 재전송 응답, 저장된 진단에는 다음 값이 함께 남는다.

| 값 | 의미 |
| --- | --- |
| `choice` | 모델이 고른 `candidate_N`, `wait_for_result`, `insufficient_evidence` |
| `chosen_transition_hash` | 후보 선택 시 graph transition ID의 SHA-256. 승인된 graph의 ID를 해시하여 대조한다. |
| `raw_confidence` | Jev 원본 confidence |
| `top_probability` | 모델이 고른 항목의 확률. UI 후보가 아니라 대기·판단 보류의 확률일 수도 있다. |
| `choice_count` | UI 후보와 대기·판단 보류 2개를 합한 전체 선택지 수 |
| `normalized_probability` | `(top_probability - 1/choice_count) / (1 - 1/choice_count)` |
| `confidence` | 원본 confidence와 normalized_probability의 최솟값. atomic 행동 후보는 페이지·Noul 값까지 포함한 최솟값 |
| `minimum_confidence` | 통과 기준 |
| `hold_reason` | `none`, `raw_confidence_below_minimum`, `normalized_probability_below_minimum`, `both_below_minimum`, `wait_for_result`, `insufficient_evidence` |
| `judgment_mode` / `question_count` | `atomic`/3 또는 `legacy`/1. 질문 수이며 HTTP 호출 수가 아니다. |
| `page_confidence` | 원본 페이지 confidence와 해당 Choice 정규화 확률의 최솟값 |
| `target_support_probability` | 원본 Noul 값. 대상 선택이나 실행 성공 확률이 아니다. |
| `page_target_consistent` | 식별한 페이지와 선택한 대상의 출발 단계가 일치하는지. 수치 기준에 먼저 걸려도 기록한다. |
| `choice_temperature` | 로컬 확률 변환에 사용한 T |
| `tempered_top_probability` / `tempered_probability_margin` / `tempered_confidence` | T 적용 후 진단값. 행동 허용 근거로 사용하지 않는다. |
| `retained` | 유효한 이전 네이티브 선택을 유지했는지. 참이면 현재 모델 응답의 새 점수가 아니다. |
| `paused` | 같은 화면에서 복구 한도에 도달하여 사용자 재시도를 기다리는지 |

`probability_margin`도 유지한다. `choice`와 transition 해시는 모델의 선택을
나타내며, 실제 클릭·경로 이동 성공의 증거가 아니다. 후보 번호는 해당 호출에서만
의미가 있다. 로컬 resolver의 `chosen_state`/`chosen_transition`은 원문으로
저장하지 않는다. 낮은 confidence에서도 허용된 진단값을 남기며 화면 원문이나
실제 노드 ID는 추가하지 않는다. 잘못된 응답·호출 실패는 `status`로 구별하며
검증을 통과하지 않은 점수·선택 항목은 기록하지 않는다.

같은 합성 화면을 클릭 없이 반복하며 런타임 동작과 Jev 진단을 기록하려면:

```sh
python scripts/diagnose_jev_screen.py --env-file .env --attempts 5 --output jev-diagnostics.json
```

같은 세션에서 최초 선택이 통과하면 다음 관측에서는 유효한 안내를 유지하므로
매번 Jev를 호출하지 않는다. 보고서의 `provider_calls`, 관측별 `called`·`retained`로
호출 횟수와 유지된 결과를 구분한다. `planned_action_present`와
`previous_action_present`는 각각 안내한 계획과 실제 이벤트 수신 여부다.
`--fresh-sessions`를 추가하면 매번 새 작업으로 판단한다. 원본 화면·응답 본문·키는
저장하지 않고 위 진단과 승인된 graph에서 해시로 찾은 선택 대상만 출력한다.
이 검사는 로컬 수정본의 실제 모델 호출이며 운영 배포나 기기 조작 검증은 아니다.
`grounded_candidate_count`는 그래프의 대상 조건에 맞는 후보가 화면에 있는지
나타낸다. 앱은 이 값이 양수이면 보류된 대상을 스크롤로 지나치지 않는다.
이 값 자체로 대상을 선택하거나 실행하지는 않는다.

화면 판단 입력은 메뉴의 제목(`label_parts`), 부가 설명과 페이지 제목의 구분
(`structure_role`), 각 후보의 현재 페이지·목적지·목표 메뉴 이름을 포함한다.
설정 목록 안의 ‘디스플레이’를 이미 진입한 페이지 제목으로 혼동하지 않도록
다음 이동 단계의 의미를 전달한다. 이 입력 보완은 서버 코드 배포가 필요하며,
자동 테스트 통과만으로 실제 Jev의 선택 정확도가 검증된 것은 아니다.

설정 변경 전 승인, 행동 이벤트 연결, 실제 설정값 기반 완료 판정은 유지한다.
Jev 선택 실패 시 규칙이 대신 조작하지 않는다. 동일 화면에서 graph의 복구 한도까지
재관측한 뒤 사용자 도움으로 전환한다. 원본 confidence와 보정 기준은 유지한다.
상세 범위·제한은 [AI1 Jev 문서](../../AI-Agent/docs/jev-ui-selection.md)를 참고한다.
