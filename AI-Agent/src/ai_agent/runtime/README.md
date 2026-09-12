# AI-Agent(1): 사용자 Runtime

Backend의 turn API가 사용하는 사용자 작업 실행 경로다. `ClickAgentEngine`이 raw text·현재 화면·사용자 행동을 받아 다음 guide를 반환하고, 후속 관측으로 직전 단계를 검증한다.

| 파일 | 실행 책임 |
| --- | --- |
| `engine.py` | turn 조율 |
| `goal_typing.py` | 사용자 의도 → Typed Goal |
| `normalizer.py`, `classifier.py` | 화면 정규화·State 판단 |
| `matcher.py`, `similarity.py` | 승인 Graph의 다음 UI target 선택 |
| `guide.py`, `contracts.py` | Safety에 따른 안내·확인·허용 Action 계약 |
| `verification.py`, `state_manager.py` | 새 화면을 이용한 직전 단계 결과 검증 |
| `replanner.py` | 현재 세션의 실패 이력·재시도·target 제외 |
| `session.py`, `types.py` | 실행 상태·입출력 계약 |
| `tasks/`, `progress.py` | 현재 지원 graph·진행도 계산 |
| `privacy.py`, `budget.py` | 개인정보 처리·turn 시간 예산 |

이 패키지는 `ai_agent.graph_proposal`을 import하지 않는다. dataset 생성, success/fail 학습 라벨 확정, graph 후보 생성·검증·승격·rollback은 [AI-Agent(2)](../graph_proposal/README.md)의 책임이다.

실행 사실과 온라인 판단은 공통 [`telemetry/`](../telemetry/README.md)의 진단 producer에 전달한다. `SUCCESS`/`DONE`은 온라인 검증 결과이며 학습 정답을 의미하지 않는다. Re-plan은 현재 세션의 선택을 바꾸며 공통 graph 정의를 수정하지 않는다.

공통 graph 입력과 관측 계약만 공유하고, Agent(2)의 분석이 끝날 때까지 사용자 응답을 기다리지 않는다. 현재 `TraceLogger`의 필터·TTL·개수 제한과 Backend의 API·세션 계약을 유지한다.

상세 실행 설계: [AI-Agent(1)](../../../../docs/ai-agent-1-runtime.md).
