# 관측 기반 Verifier와 민감 모드

구현: 2026-11-19. 기존 검증된 설정 그래프와 실행 승인·최종 사용자 확인 절차는 유지한다.
이 변경은 새 LLM Planner, RAG, 무그래프 실행기를 배포하는 작업이 아니다.

## 성공 조건 여섯 유형

구현: `AI-Agent/src/ai_agent/runtime/verification_conditions.py`.

| kind | 검사 근거 | 성공으로 오해하면 안 되는 것 |
| --- | --- | --- |
| `screen_entered` | 허용 앱/활성 창의 독립 제목 + 핵심 컨트롤 | 목록 안의 메뉴 문구 |
| `element_visible` | 현재 화면의 고유하고 가시적인 네이티브 대상 | 클릭 실행·전체 작업 완료 |
| `state_changed` | 같은 대상의 조작 전 checked와 조작 후 기대 상태 | 모델이 추측한 ON/OFF |
| `value_changed` | 같은 대상·숫자 출처·단위·범위에서 요청 방향으로 변화 | 드래그 이벤트나 출처가 다른 슬라이더 값 |
| `input_completed` | 현재 필드의 로컬 값 토큰이 기대 값 토큰과 일치하고 명시적 무효 상태가 아님 | 유효한 형식이라는 사실만으로 올바른 입력/검색/제출 완료 주장 |
| `task_completed` | 현재 관측에서 원자 최종 조건들의 AND | 과거 노드의 성공 비트만 합치기 |

`Condition.from_dict()`는 닫힌 형식만 허용한다. 코드, 임의 검사식, `approved` 같은 모델 승인 필드, 중첩 완료 조건은 거부한다. 최대 원자 조건 16개다. `evaluate()`는 서버가 별도로 승인한 조건에만 사용한다. 역직렬화만으로 승인되는 것이 아니다. 목표와 조건의 의미 정합성을 검토하는 새 Planner/Decisions 경로는 아직 구현 범위 밖이다.

공통 검사기 입력 `Frame`/`ActionProof`는 실행기가 생성하는 내부 근거다. 모델이나 HTTP 요청의 임의 `complete`/`approved` 플래그를 근거로 삼으면 안 된다. 현재는 신규 계획용 검사기를 라이브러리로 제공하고, 기존 설정의 실제 성공 판정은 기존 `verification.py`, `task_success.py`의 더 구체적인 네이티브 검사로 유지한다.

숫자 비교에서는 슬라이더 자체 값과 시스템 적용 값을 구별해야 한다. 새 공통 검사기는 제공한 `value_source`의 변화만 검증한다. 예를 들어 실제 FONT_SCALE을 확인하려면 실행기가 읽은 FONT_SCALE 근거를 사용한다. 기존 글자 크기·밝기·음량 경로는 여전히 슬라이더와 시스템 적용 값 양쪽을 검사한다.

입력 값 토큰은 민감 값의 익명화를 보장하는 수단이 아니다. 새로운 입력 수집/전송을 허용하지 않으며, 로컬 일시 비교용이다. 토큰·입력 원문은 메타데이터, 로그, GPT 요청에 넣지 않는다. 일반 설정 Privacy Guard는 입력 화면을 계속 차단한다.

## 결과 다섯 상태

| status | 의미 | 권장 다음 처리 |
| --- | --- | --- |
| `SUCCESS` | 관측 근거로 해당 조건 확인 | 다음 Subgoal. 기술적 변화와 사용자 만족 확인은 별개 |
| `WAITING` | 사용자 행동/로딩/반영 대기 | 같은 목표 유지; `waiting_for=user/observation` 구분 |
| `UNKNOWN` | 관측·대상·출처·시간·승인 근거 부족 | 재관측/사용자 확인. 실패 횟수 증가 금지 |
| `FAILED` | 연결된 행동 뒤 안정된 두 관측에서도 조건 미달 | 기존 재선택·복구 한도 사용 |
| `BLOCKED` | 확인된 권한/인증/앱 제약 | 사용자 직접 진행 또는 재계획. 자동 권한 승인 없음 |

빈 트리, 미완료 관측, 중복·과거 관측, 다른 episode, 맞지 않는 숫자 출처/범위는 `UNKNOWN`이다. 로딩이라고 알려진 화면만 `WAITING`으로 구분한다. 실패에는 연결된 행동과 서로 다른 두 개의 안정된 사후 관측이 필요하다. 행동 전에 이미 관측된 값 변화를 나중에 도착한 행동에 귀속하지 않는다.

## 기존 API 연결

`metadata.verification.assessment`에 `verification-assessment.v1`을 추가했다. 기존 `result.status` enum은 유지하여 앱·체크포인트와의 호환성을 보존한다.

```json
{
  "schema_version": "verification-assessment.v1",
  "status": "WAITING",
  "condition_type": null,
  "reason_code": "no_correlated_action",
  "waiting_for": "user",
  "next_action": "wait",
  "counts_as_failure": false,
  "task_completed": false
}
```

기존 SUCCESS와 완료 결과를 보수적으로 투영한다. 기술적 변경 SUCCESS여도 기존 API는 최종 만족 질문 `WAITING_RESULT`를 유지한다. 차단 안내가 표시되었다는 사실만으로 관측 부족을 권한 BLOCKED로 바꾸지 않는다. 이 메타데이터만으로 자동 실행하거나 실패 예산을 바꾸지 않는다.

## GPT 보조 검증

`Backend/app/services/gpt_verifier.py`, OpenAI Responses API의 구조화 출력 사용.

- `OPENAI_API_KEY`: 기존 운영자 API 키 사용. 키는 출력하지 않는다.
- `OPENAI_VERIFIER_ENABLED`: 기본 false. 이 Mac의 기존 `.env`에는 true로 설정했다.
- `OPENAI_VERIFIER_MODEL`: 기본 `gpt-6.2-sol`, 환경 변수로 변경 가능.
- `OPENAI_VERIFIER_TIMEOUT_SECONDS`: 기본 최대 8초이며 전체 turn 잔여 시간보다 길게 사용하지 않는다.

새로 연결된 행동 뒤 특정 UNKNOWN 사유에서만 호출한다. 성공, 사용자 대기, 빈 관측, 민감 화면, 재전송된 행동은 호출하지 않는다. 현재는 설정 경로만 대상이며 쿠팡·정부24·임의 앱 전송을 새로 허용하지 않는다.

설정 Privacy Guard를 통과한 고정 어휘의 최소 노드만 전송한다. 원문 요청, 임의 설명, 계정, 입력값/토큰, raw node/resource ID, 스크린샷, API 키는 payload에 넣지 않는다. `store=false`를 사용하지만 이것 자체가 업체의 ZDR 계약을 의미하지 않는다.

응답은 `supported/contradicted/insufficient_evidence`, 고정 이유 코드, 관측된 임시 ID 참조로 제한한다. 메타데이터에는 고정 코드와 근거 개수만 남긴다. `metadata.verification.model_review`는 advisory이고 성공 상태, 선택한 가이드, 실행 승인, 실패 예산을 변경할 권한이 없다. 지원하는 대상이 보인다는 판단과 실제 설정 변화는 다르다. API 오류·시간 초과·거부·허구 ID는 기존 UNKNOWN을 유지한다. 자동 재시도나 다른 업체 fallback은 없다.

실제 연결 검사는 개인정보 없는 작성된 ‘글자 크기’ 한 노드로 수행했다. 3초 제한에서는 timeout, 8초 제한에서는 GPT 응답을 받았다. 이는 성능/정확도 평가나 실기기 성공 증거가 아니다.

```sh
cd Backend
.venv/bin/python scripts/check_gpt_verifier.py --live --env-file .env
```

공식 API 근거: [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [GPT-6.2 Sol](https://developers.openai.com/api/docs/models/gpt-6.2-sol).

## Android Privacy Guard와 재개

기존 `SettingsScreenPrivacy.filter()`를 바탕으로 로컬 모드를 구분한다.

- `PUBLIC_MINIMIZED`: 필터를 통과한 화면. 실제 POST 직전에도 다시 필터링한다.
- `SENSITIVE`: 입력/보호 화면. 화면 정보 전송과 자동 조작을 멈추고 직접 진행 안내를 표시한다.
- `UNVERIFIED`: 빈/미확인/미지원 화면. 일반 화면으로 취급하지 않는다.

설정 Overlay는 같은 Guard로 사전 검사한다. 민감 모드에서는 기존 목표와 진행 상태를 보존하고, 이전 화면의 강조·승인·행동 바인딩·vision 대기를 폐기한다. 실행 중인 로컬 제스처 루프도 중단하고, 민감 모드 진입 후 도착한 과거 공개 화면 응답이 안내를 덮어쓰지 못하게 한다. 이미 운영체제에 전달된 터치를 되돌린다는 의미는 아니다. 창이 dialog로 표시돼도 보호 제목을 우선한다. 민감 모드에서 읽을 수 없는 화면으로 전환됐다는 이유만으로 재개하지 않는다. 다시 공개 화면으로 검증되고 기존 경로에 적합할 때 새로운 관측과 후보로 이어간다. 사용자가 명시적으로 일시정지/취소한 작업은 자동 재개하지 않는다.

전송 직전 Guard에서 민감 화면이 발견된 경우에도 작업을 영구 STOPPED로 종료하지 않고 민감 모드에서 대기한다. 이 동작은 설정 경로의 변경이다. 정부24·쿠팡의 별도 기존 필터/수동 인계 정책은 유지한다. 모든 임의 앱/모든 민감 화면을 완벽히 탐지한다는 의미는 아니다. 현재 정책 밖 화면은 허용하지 않는다.

## 확인 범위

- 공통 검사/기존 runtime·삼성 글자 크기 회귀 테스트.
- HTTP 응답·체크포인트·최종 사용자 확인·기존 설정값 검사.
- GPT의 최소화·민감 입력 차단·유효하지 않은 응답·관측 ID 참조·중복 행동 차단.
- Android 필터·환경 분류·Overlay의 민감 모드/미확인 화면 대기/안전 화면 재개/사용자 일시정지 보존.
- 실제 여러 기기/OEM에서의 end-to-end 검증은 아직 수행하지 않았다. 서버 배포/Play 업로드는 수행하지 않았다.
