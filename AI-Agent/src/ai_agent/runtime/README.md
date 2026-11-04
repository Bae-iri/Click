# AI-Agent(1): 사용자 Runtime

Backend의 turn API가 사용하는 사용자 작업 실행 경로다. **2026-10-27 구조 정리:** typed 경로는 Backend가 DB 본문에서 선택·복원한 graph를 adapter에 주입해 실행한다. 목표 확인·분류는 FE/Backend, DB graph 선택·복원은 Backend, 화면 판단·Guide/Action → Verify → graph 내부 Re-plan은 AI1의 책임이다. [내부 전달 계약](../../../../docs/backend-task-graph-dispatch.md)을 따른다.

실행 진입점은 `ClickAgentEngine.handle_turn()`이다. raw goal 분류와 기본 registry는 기존 요청·테스트에서 사용하므로 유지한다. 호출되지 않던 상위 `goal_typing/`, `execution/`, `replanning/`, `verification/`, `workflows/` 골격은 제거했다. 실제 tap/drag 수집 및 글자 배율 검증 코드는 연결됐으나 실기기 완료 검증과 제품 gold 기반 품질 평가는 남아 있다.

| 파일 | 실행 책임 |
| --- | --- |
| `engine.py` | turn 조율 |
| `observation_context.py` | 요청·기기·OCR 관측에서 화면 크기와 앱 문맥 해석 |
| `diagnostics.py` | 응답·실행 기록의 관측/복구/처리 단계 진단 정보 생성 |
| `goal_typing.py` | legacy raw goal → Typed Goal; typed 경로의 목표 확정은 FE/Backend 담당 |
| `normalizer.py`, `classifier.py` | 화면 정규화·State 판단 |
| `semantic_state.py` | 규칙으로 분류하지 못한 화면에 주입된 의미 해석기를 적용하고 결과 검증 |
| `matcher.py`, `targeting.py`, `similarity.py` | 역할·앱/window 필터, 공통 graph selector, 동점 선택 보류 |
| `screen_resolver` 주입 지점 | Backend의 Jev가 현재 graph 단계와 UI 대상을 함께 결정; 실패 시 재관측 |
| `ui_selection.py`, `jev_ui.py` | 이전 보조 선택 API의 명시적 주입 호환성; Backend 주 판단 경로에서는 사용하지 않음 |
| `guide.py`, `contracts.py` | Safety에 따른 안내·확인·허용 Action 계약 |
| `verification.py`, `state_manager.py` | 새 화면을 이용한 직전 단계 결과 검증 |
| `recovery.py`, `replanner.py` | 실제 시도 실패·제외 유지·명시된 경로 우선순위·복구 제한·Backend 반환 |
| `replay.py` | 선택적 비식별 관측 내보내기·결정적 실패 재현·오선택/종료/완료/복구 평가 |
| `session.py`, `types.py` | 실행 상태·입출력 계약 |
| `tasks/`, `progress.py` | 로컬 seed/legacy graph, 전달 graph 기반 진행도·관측 경로 계산 |
| `privacy.py`, `budget.py` | 개인정보 처리·turn 시간 예산 |
| `vision.py` | 요청별 동의·개인정보 검사를 통과한 기기 내 OCR 요소를 검증하고 화면 노드로 변환 |

`vision.py`는 외부 LLM/VLM을 호출하지 않는다. 기존 `fallback/llm_vlm_fallback.py`를 이동하고 클래스를 `VisionFallback`으로 명명했다. raw 이미지 차단·지원 provider·confidence 기준은 유지한다. OCR 계약 회귀는 `python scripts/evaluate_vision_fallback.py --output data/local/vision_evaluation.json`으로 실행하며 재생성 가능한 결과는 Git에 저장하지 않는다.

Jev 주 판단은 Backend 환경의 `AI1_UI_SELECTOR_PROVIDER=jev`로 활성화한다. Backend가 `screen_resolver`를 주입하며 자체 화면 LLM이나 보조 선택기를 함께 호출하지 않는다. 최소화된 화면 텍스트·관계·값과 graph를 바탕으로 Jev가 단계와 대상을 결정하고, 완료 판정과 설정 변경 전 확인은 기존 실행 계약을 따른다. 환경변수 생략 시 `rules`이며 예제 배포 설정은 `jev`다. [설정·호출 흐름·검증 방법](../../../docs/jev-ui-selection.md)을 참고한다.

Backend의 기존 guidance adapter가 사용하는 `observation/`, `matching/`, `guide/`, `safety/`와 `schemas.py`는 상위 패키지에 남아 있다. `user_modeling/`은 Backend 개인화에서 사용한다. 각 영역의 책임은 [패키지 구조](../../../README.md)를 참고한다.

이 패키지는 `ai_agent.graph_proposal`을 import하지 않는다. dataset 생성, success/fail 학습 라벨 확정, graph 후보 생성·검증·승격·rollback은 [AI-Agent(2)](../graph_proposal/README.md)의 책임이다.

실행 사실과 온라인 판단은 공통 [`telemetry/`](../telemetry/README.md)의 진단 producer에 전달한다. `SUCCESS`/`DONE`은 온라인 검증 결과이며 학습 정답을 의미하지 않는다. Re-plan은 현재 세션의 선택을 바꾸며 공통 graph 정의를 수정하지 않는다.

공통 graph 입력과 관측 계약만 공유하고, Agent(2)의 분석이 끝날 때까지 사용자 응답을 기다리지 않는다. 현재 `TraceLogger`의 필터·TTL·개수 제한과 Backend의 API·세션 계약을 유지한다.

상세 실행 설계: [AI-Agent(1)](../../../../docs/ai-agent-1-runtime.md).

평가 데이터는 [정답 데이터·개발 계획](../../../../docs/task-graph-data-plan.md)을 따른다. ColorBench의 경로·MobiFlow의 규칙은 검수한 보조 자료이며, 우리 완료 정확도는 실제 대상 환경의 독립 gold로 평가한다. Backend graph 선택과 AI1 내부 경로 선택을 구분하고 weak label·graph candidate를 runtime 입력으로 소비하지 않는다.

현재 검증 상태·행동/관측 ID·완료 predicate·FE 연결 제한: [AI1 실행 정책](../../../../docs/ai-agent-1-runtime-policy.md).
