# AI1 주 판단 경로: Jev

쿠팡 목표의 후보 구성과 직접 진행 경계는 [쿠팡 실행 안내](../../docs/tasks/coupang.md)를 따른다. 같은 `JevScreenResolver`가 앱별 처리로 분기하며, 아래 설정용 슬롯·용어·조작 검증을 쿠팡에 대신 사용하지 않는다.

Backend의 `RuntimeAgentAdapter`가 `AI1_UI_SELECTOR_PROVIDER=jev`를 읽으면
`JevScreenResolver`를 AI1의 `screen_resolver`로 주입한다. Jev가 화면 정보와
승인된 그래프를 보고 **현재 단계와 실제 조작 대상**을 함께 선택한다.
요청 목표를 해석하는 `GOAL_CLASSIFIER_PROVIDER`와는 독립적이다.

## 실행 흐름

1. 접근성 트리와 동의·개인정보 검사를 통과한 OCR 결과를 정규화한다.
2. 그래프 전체에서 역할·앱·활성화·화면 영역 조건에 맞는 조작 대상을 만든다.
   문자열 매칭 점수나 현재 단계 추정으로 후보를 미리 제한하지 않는다.
3. Jev가 메뉴 텍스트, 인접 설명, 부모 관계, 값, 상대 위치와 그래프를 읽어
   단계 + 대상 조합을 선택한다. 규칙이 잘못 파악한 단계도 수정할 수 있다.
4. 선택한 관측 노드의 실제 ID와 좌표를 로컬에서 복원한다. 이를 반영한 단계로
   직전 조작의 결과를 검증하므로 여러 단계를 직접 진행한 경우도 재동기화한다.
5. 안내 생성, 설정 변경 전 확인, 전후 값과 조작 이벤트를 이용한 완료 검증을 수행한다.
   Jev의 높은 confidence 자체로 작업을 완료 처리하지 않는다.

주 판단 모드는 보조 `JevUiSelector`나 자체 화면 LLM을 함께 호출하지 않는다.
`ui_selection.py`/`jev_ui.py`의 이전 API는 명시적 주입을 쓰는 호환성 테스트용으로
남아 있으며 Backend 경로에서는 사용하지 않는다.

응답 실패·낮은 신뢰도·선택 보류는 각각 `MODEL_UNAVAILABLE`, `LOW_CONFIDENCE`,
`MODEL_ABSTAINED` 복구 사유로 전달한다. 실행 이벤트 없이 모델이 대기를 고르면
`MODEL_WAIT_WITHOUT_ACTION`으로 구분한다. 규칙이 버튼을 찾았어도 이 보류를
정상 진행으로 바꾸지 않는다. 동일 화면의 재관측은 graph의 `recovery_limit`과
시간 예산으로 제한하고, 한도 이후에는 자동 호출 없이 사용자 재시도·수동 이동·취소를
기다린다. 실제 조작 실패 예산과 화면 판단 복구 예산은 별개다.

이미 기준을 통과한 네이티브 안내는 실행 전 화면·기기 설정값이 그대로이고 대상의
ID·window·resource·좌표·활성 상태·의미 식별자가 모두 유효하면 유지한다.
이 경우 `ui_selection.called=false`, `retained=true`이며 이전 선택의 진단값임을
표시한다. 승인 대기 안내를 유지해도 승인이 자동 부여되지는 않는다.
화면이나 대상이 달라지면 재사용하지 않는다. OCR 대상은 이 유지 경로를 사용하지 않는다.

Jev 입력의 `planned_action`은 안내한 계획이고, `previous_action`은 상관관계가
확인된 행동 이벤트가 있을 때만 전달한다. 클릭하지 않은 같은 화면을 조작 실패나
결과 반영 대기로 설명하지 않는다. 실제 조작 결과 대기 중에는 추가 조작을 안내하지 않는다.
명시적 `manual_override` 재시도는 대기 잠금과 이전 시도를 해제하되 소비한 실패 예산과
제외 대상을 보존한다. 다른 세션이나 오래된 재시도 이벤트로는 잠금을 해제하지 않는다.

`runtime/primary.py`는 주 판단·유지·보류 결과를 반환하고, `runtime/recovery.py`는
복구를 결정한다. `runtime/decision.py`가 개인정보 차단·거절·완료·조작 결과 대기·관측·
선택의 우선순위로 최종 안내를 한 번 결정한다. 엔진은 진행도를 포함한 세션 상태를
turn 마지막에 한 번 저장한다.

## 백엔드 담당자 설정

키는 **AI1을 실행하는 백엔드 담당자의 기존 환경**에 있는 것을 그대로 사용한다.
프론트엔드 개발자의 컴퓨터나 Android 앱에 복사할 필요가 없다.

```dotenv
AI1_UI_SELECTOR_PROVIDER=jev
TYPESAFE_API_KEY=<기존 백엔드 비밀값 유지>
JEV_MODEL=jev-1.14.0
JEV_MIN_CONFIDENCE=0.8
```

기존 `.env`의 DB·STT 설정을 덮어쓰지 말고 필요한 항목만 추가한다.
키 이름은 `JEV_API_KEY`가 아닌 `TYPESAFE_API_KEY`다. 주 판단 모드는
`JEV_MIN_CONFIDENCE`를 사용하며 이전 보조 선택기의 `JEV_UI_MIN_CONFIDENCE`는 사용하지 않는다.

백엔드 담당자가 Backend 디렉터리에서 실행하는 예:

```sh
python -m uvicorn app.main:app --env-file .env --host 0.0.0.0 --port 8000
```

`.env`는 Python 코드가 자동으로 읽지 않는다. 기존 실행 도구가 환경변수를
주입한다면 그 방식을 유지한다. 설정 변경 후 백엔드 프로세스를 재시작한다.
Render에서는 해당 웹 서비스의 Environment에 동일한 항목을 넣고 새 코드를 배포한다.
Compose에서는 저장소 루트 `.env` 값을 `docker-compose.dev.yml`이 전달한다.
`.env`는 Git과 Docker 빌드 컨텍스트에서 제외된다.

환경변수를 생략하면 기존 `rules` 경로가 유지되며 `local`도 호환 별칭이다.
제공하는 `.env.example`과 `render.yaml`은 Jev 주 판단 모드를 지정한다.
`jev`를 선택했는데 키·모델 설정이 없거나 잘못되면 어댑터 구성 오류로 중단한다.

## 연결 확인

백엔드 담당자 환경에서 실행한다. 합성 설정 화면으로 실제 Jev 호출과 주 판단
경로를 확인하며 실제 사용자 화면은 보내지 않는다.

```sh
cd Backend
python scripts/check_jev_screen.py --env-file .env
```

성공하면 `mode: primary`, `provider: jev`, `called: true`, `status: selected`,
`target_node_id: display`와 종료 코드 0을 반환한다.
API 응답에서도 `metadata.debug.ui_selection`로 동일한 호출 상태를 확인한다.
키·원문 응답·노드 텍스트는 진단 로그에 넣지 않는다.

## 경계와 검증

- 좌표·실제 노드 ID·세션 ID·음성·이미지를 전송하지 않는다. 화면 텍스트는
  기존 입력 필드 제거와 패턴 마스킹을 거친다. 완전한 익명화를 보장하지는 않는다.
- 후보는 최대 250개, 관련 관측 노드는 160개, 요청·응답은 64 KiB 이내다.
  중복 ID, 여러 window, 비활성·화면 밖 대상, 금지된 전이는 선택하지 않는다.
- OCR 대상의 시각적 확인과 실제 조작·설정값 증거에 따른 완료 검증을 유지한다.
- 네트워크 timeout은 8초와 남은 turn 예산 중 작은 값이다. 늦은 결과를 버리고
  Backend 전체 turn timeout도 유지한다. 자동 API 재시도는 하지 않는다.
- 모델 버전, 응답 형식, 확률 분포, confidence, 선택 후보를 검증한다.
  confidence 임계값은 실기기 정답률을 보장하는 수치가 아니다.

자동 테스트는 합성 화면과 가짜 HTTP 응답을 사용한다.
`Backend/tests/test_screen_jev.py`, `Backend/tests/test_jev_primary.py`에서
주 경로 연결, 단계 교정·건너뛰기, HTTP 실패 시 조작 중단, 확인 보존,
관측값 기반 완료와 오완료 방지, 후보 경계 및 시간 제한을 검증한다.

API 계약: [Choice](https://docs.typesafe.ai/primitives/choice),
[System One](https://docs.typesafe.ai/api/system-one).
배포 설정: [Render 환경변수](https://render.com/docs/configure-environment-variables).
