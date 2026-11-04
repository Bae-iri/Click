# AI1 주 판단 경로: Jev

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

응답 실패·낮은 신뢰도·선택 보류 때 규칙의 버튼 선택으로 자동 진행하지 않는다.
대상 없는 재관측 안내를 반환한다. 관측 수집용 OCR 동의 흐름은 유지한다.
직전 조작이 아직 반영되지 않았거나 같은 화면에서 실패한 경우 추가 조작을
멈추며, 새로운 화면을 관측하면 다시 판단한다.

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
