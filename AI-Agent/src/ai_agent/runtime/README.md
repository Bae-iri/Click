# AI-Agent(1): 사용자 Runtime

Backend의 turn API가 사용하는 사용자 작업 실행 경로다. **2026-10-10 코드 확인:** typed 경로는 Backend가 DB 본문에서 선택·복원한 graph를 adapter에 주입해 실행한다. Goal/Command 확정은 FE, DB graph 선택·복원은 Backend, 화면 판단·Guide/Action → Verify → graph 내부 Re-plan은 AI1의 책임이다. [내부 전달 계약](../../../../docs/backend-task-graph-dispatch.md)을 따른다. `ClickAgentEngine`에는 raw goal 분류·기본 registry 경로가 남아 있어 완전한 입력 계약 정리는 후속 작업이다. 실제 tap/drag 수집 및 글자 배율 검증 코드는 연결됐으나 실기기 완료 검증은 남아 있다. 기존 Backend 충돌과 [UI 오선택 재현](../../../../docs/reviews/2026-10-10-ui-highlight-revalidation.md) 때문에 전체 통합 완료로 표시하지 않는다.

| 파일 | 실행 책임 |
| --- | --- |
| `engine.py` | turn 조율 |
| `goal_typing.py` | legacy 사용자 의도 → Typed Goal; FE 이관 및 runtime 호출 제거 대상 |
| `normalizer.py`, `classifier.py` | 화면 정규화·State 판단 |
| `matcher.py`, `targeting.py`, `similarity.py` | 역할·앱/window 필터, 공통 graph selector, 동점 선택 보류 |
| `guide.py`, `contracts.py` | Safety에 따른 안내·확인·허용 Action 계약 |
| `verification.py`, `state_manager.py` | 새 화면을 이용한 직전 단계 결과 검증 |
| `recovery.py`, `replanner.py` | 실제 시도 실패·제외 유지·명시된 경로 우선순위·복구 제한·Backend 반환 |
| `replay.py` | 선택적 비식별 관측 내보내기·결정적 실패 재현·오선택/종료/완료/복구 평가 |
| `session.py`, `types.py` | 실행 상태·입출력 계약 |
| `tasks/`, `progress.py` | 로컬 seed/legacy graph, 전달 graph 기반 진행도·관측 경로 계산 |
| `privacy.py`, `budget.py` | 개인정보 처리·turn 시간 예산 |

이 패키지는 `ai_agent.graph_proposal`을 import하지 않는다. dataset 생성, success/fail 학습 라벨 확정, graph 후보 생성·검증·승격·rollback은 [AI-Agent(2)](../graph_proposal/README.md)의 책임이다.

실행 사실과 온라인 판단은 공통 [`telemetry/`](../telemetry/README.md)의 진단 producer에 전달한다. `SUCCESS`/`DONE`은 온라인 검증 결과이며 학습 정답을 의미하지 않는다. Re-plan은 현재 세션의 선택을 바꾸며 공통 graph 정의를 수정하지 않는다.

공통 graph 입력과 관측 계약만 공유하고, Agent(2)의 분석이 끝날 때까지 사용자 응답을 기다리지 않는다. 현재 `TraceLogger`의 필터·TTL·개수 제한과 Backend의 API·세션 계약을 유지한다.

상세 실행 설계: [AI-Agent(1)](../../../../docs/ai-agent-1-runtime.md).

평가 데이터는 [정답 데이터·개발 계획](../../../../docs/task-graph-data-plan.md)을 따른다. ColorBench의 경로·MobiFlow의 규칙은 검수한 보조 자료이며, 우리 완료 정확도는 실제 대상 환경의 독립 gold로 평가한다. Backend graph 선택과 AI1 내부 경로 선택을 구분하고 weak label·graph candidate를 runtime 입력으로 소비하지 않는다.

현재 검증 상태·행동/관측 ID·완료 predicate·FE 연결 제한: [AI1 실행 정책](../../../../docs/ai-agent-1-runtime-policy.md).
