# AI1 Jev 주 판단 연결

`AI1_UI_SELECTOR_PROVIDER=jev`이면 `RuntimeAgentAdapter`가 `JevScreenResolver`를
AI1의 주 판단기로 주입한다. 현재 화면 단계와 UI 대상을 함께 선택한다.

백엔드 담당자의 기존 `TYPESAFE_API_KEY`를 사용한다. 프론트엔드 개발자에게
키를 전달할 필요는 없다. 기존 `.env`에 다음 설정을 반영한다.

```dotenv
AI1_UI_SELECTOR_PROVIDER=jev
TYPESAFE_API_KEY=<기존 값 유지>
JEV_MODEL=jev-1.14.0
JEV_MIN_CONFIDENCE=0.8
```

`.env` 사용 시 서버 실행 명령이 파일을 읽는지 확인한다.
예: Backend 폴더에서 `python -m uvicorn app.main:app --env-file .env`.
변경된 Backend와 AI-Agent 코드를 함께 배포하고 프로세스를 재시작한다.
`GOAL_CLASSIFIER_PROVIDER`는 요청 목표 해석의 별도 설정이며 여기서 바꾸지 않는다.

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
| `confidence` | 원본 confidence와 보정값 중 작은 최종값 |
| `minimum_confidence` | 통과 기준 |
| `hold_reason` | `none`, `raw_confidence_below_minimum`, `normalized_probability_below_minimum`, `both_below_minimum`, `wait_for_result`, `insufficient_evidence` |
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
