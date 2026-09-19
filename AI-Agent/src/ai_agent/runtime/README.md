# AI-Agent(1): 사용자 Runtime

Backend의 turn API가 사용하는 사용자 작업 실행 경로다. **새 책임 경계에서는 Backend가 DB에서 선택해 전달한 Graph·확정 목표·실행 상태를 받아 Guide/Action → Verify → graph 내부 Re-plan을 수행한다.** Goal/Command 분류는 FE, DB graph 선택·복원은 Backend다. [내부 전달 계약](../../../../docs/backend-task-graph-dispatch.md)을 따른다. [FE 이관·typed 계약](../../../../docs/fe-goal-command-contract.md)은 설계이며 아직 코드에 적용되지 않았다. Goal Typing·graph lookup을 주입 graph 실행으로 바꾸는 것은 후속 구현이다. 아래는 전환 전 현재 구현이다. `ClickAgentEngine`이 raw text·현재 화면·사용자 행동을 받아 다음 guide를 반환하고, 후속 관측으로 직전 단계를 검증한다.

| 파일 | 실행 책임 |
| --- | --- |
| `engine.py` | turn 조율 |
| `goal_typing.py` | legacy 사용자 의도 → Typed Goal; FE 이관 및 runtime 호출 제거 대상 |
| `normalizer.py`, `classifier.py` | 화면 정규화·State 판단 |
| `matcher.py`, `targeting.py`, `similarity.py` | 역할·앱/window 필터, 공통 graph selector, 동점 선택 보류 |
| `guide.py`, `contracts.py` | Safety에 따른 안내·확인·허용 Action 계약 |
| `verification.py`, `state_manager.py` | 새 화면을 이용한 직전 단계 결과 검증 |
| `replanner.py` | 실제 시도 실패·제외 유지·명시된 경로 우선순위·복구 제한·Backend 반환 |
| `session.py`, `types.py` | 실행 상태·입출력 계약 |
| `tasks/`, `progress.py` | 현재 Python graph·진행도; 전환 후 seed/fixture 및 전달 graph 진행도 계산 |
| `privacy.py`, `budget.py` | 개인정보 처리·turn 시간 예산 |

이 패키지는 `ai_agent.graph_proposal`을 import하지 않는다. dataset 생성, success/fail 학습 라벨 확정, graph 후보 생성·검증·승격·rollback은 [AI-Agent(2)](../graph_proposal/README.md)의 책임이다.

실행 사실과 온라인 판단은 공통 [`telemetry/`](../telemetry/README.md)의 진단 producer에 전달한다. `SUCCESS`/`DONE`은 온라인 검증 결과이며 학습 정답을 의미하지 않는다. Re-plan은 현재 세션의 선택을 바꾸며 공통 graph 정의를 수정하지 않는다.

공통 graph 입력과 관측 계약만 공유하고, Agent(2)의 분석이 끝날 때까지 사용자 응답을 기다리지 않는다. 현재 `TraceLogger`의 필터·TTL·개수 제한과 Backend의 API·세션 계약을 유지한다.

상세 실행 설계: [AI-Agent(1)](../../../../docs/ai-agent-1-runtime.md).

현재 검증 상태·행동/관측 ID·완료 predicate·FE 연결 제한: [AI1 실행 정책](../../../../docs/ai-agent-1-runtime-policy.md).
