# AI-Agent 설계 문서 안내

AI-Agent는 사용자 작업을 수행하는 **AI-Agent(1)**과 runtime 데이터로 내부 데이터셋·Task Graph를 만드는 **AI-Agent(2)**로 분리한다.

상세 설계의 기준 문서는 루트 `docs/`에서 관리한다. 이 경로는 기존 링크를 유지하기 위한 진입점이다.

- [전체 구조와 책임 경계](../../docs/ai-agent-architecture.md)
- [FE Goal Type·Command Type 전달 계약](../../docs/fe-goal-command-contract.md)
- [AI-Agent(1): 사용자 작업 수행 Runtime Agent](../../docs/ai-agent-1-runtime.md)
- [AI-Agent(2): Runtime Data / Graph Proposal Agent](../../docs/ai-agent-2-graph-proposal.md)
- [MVP-01: 글자 크기 확대](../../docs/tasks/font-size-increase.md)

FE는 Goal/Command를 확정하고, AI-Agent(1)은 typed 입력 검증 → 승인 Task Graph 선택·복원 → 화면 관찰 → guide/action → 결과 검증 → Re-plan을 담당한다. 현재 raw-goal 코드 경로를 바꾸는 구현은 별도 전환 대상이다. AI-Agent(2)는 전체 runtime 데이터 재구성·success/fail 라벨링·dataset 생성·Task Graph 추가 및 업데이트·후보 평가를 담당한다.

기존 문서의 실행 상세와 구현 평가는 AI-Agent(1) 문서로 통합하고, graph learning/proposal은 AI-Agent(2) 문서에서 확장했다. 실제 코드도 `runtime/`, `graph_proposal/`, 공통 `telemetry/`로 분리했다. 자동 라벨링·학습·graph 배포는 별도 구현 대상이며, [파일 구조](../README.md)에서 현재 상태를 확인할 수 있다.
