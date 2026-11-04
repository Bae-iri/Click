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

설정 변경 전 승인, 행동 이벤트 연결, 실제 설정값 기반 완료 판정은 유지한다.
Jev 선택 실패 시 규칙이 대신 조작하지 않고 재관측한다.
상세 범위·제한은 [AI1 Jev 문서](../../AI-Agent/docs/jev-ui-selection.md)를 참고한다.
