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
연결 실패가 아니라 Jev가 응답했지만 선택을 보류한 상태다. `confidence`,
`minimum_confidence`, `top_probability`, `probability_margin`과
`chosen_state`/`chosen_transition` 또는 `chosen_outcome`으로 보류 이유를 확인한다.
낮은 confidence에서도 이 진단값을 남기며 화면 원문이나 실제 노드 ID는 기록하지 않는다.
`grounded_candidate_count`는 그래프의 대상 조건에 맞는 후보가 화면에 있는지
나타낸다. 앱은 이 값이 양수이면 보류된 대상을 스크롤로 지나치지 않는다.
이 값 자체로 대상을 선택하거나 실행하지는 않는다.

화면 판단 입력은 메뉴의 제목(`label_parts`), 부가 설명과 페이지 제목의 구분
(`structure_role`), 각 후보의 현재 페이지·목적지·목표 메뉴 이름을 포함한다.
설정 목록 안의 ‘디스플레이’를 이미 진입한 페이지 제목으로 혼동하지 않도록
다음 이동 단계의 의미를 전달한다. 이 입력 보완은 서버 코드 배포가 필요하며,
자동 테스트 통과만으로 실제 Jev의 선택 정확도가 검증된 것은 아니다.

설정 변경 전 승인, 행동 이벤트 연결, 실제 설정값 기반 완료 판정은 유지한다.
Jev 선택 실패 시 규칙이 대신 조작하지 않고 재관측한다.
상세 범위·제한은 [AI1 Jev 문서](../../AI-Agent/docs/jev-ui-selection.md)를 참고한다.
