# AI-Agent 설계 문서 안내

Vision fallback의 매 사용 동의, FE·BE 필드, 개인정보 마스킹 및 실패 대응은 [실행 계약](../../docs/ai-agent-vision-fallback.md)을 따른다.

AI-Agent는 사용자 작업을 수행하는 **AI-Agent(1)**과 runtime 데이터로 내부 데이터셋·Task Graph를 만드는 **AI-Agent(2)**로 분리한다.

상세 설계의 기준 문서는 루트 `docs/`에서 관리한다. 이 경로는 기존 링크를 유지하기 위한 진입점이다.

- [전체 구조와 책임 경계](../../docs/ai-agent-architecture.md)
- [Task Graph 정답 데이터·개발 계획](../../docs/task-graph-data-plan.md)
- [Backend DB Graph 선택·전달](../../docs/backend-task-graph-dispatch.md)
- [FE Goal Type·Command Type 전달 계약](../../docs/fe-goal-command-contract.md)
- [AI-Agent(1): 사용자 작업 수행 Runtime Agent](../../docs/ai-agent-1-runtime.md)
- [AI-Agent(2): Runtime Data / Graph Proposal Agent](../../docs/ai-agent-2-graph-proposal.md)
- MVP-01: 글자 크기 확대

FE는 Goal/Command를 확정하고 Backend는 DB graph 본문을 선택·검증·버전 고정·복원해 전달한다. AI1은 화면 관찰 → guide/action → 검증 → graph 내부 Re-plan을 담당한다. 2026-10-10 현재 DB loader는 구현되어 있고 legacy raw-goal 정리는 남아 있다. [UI 재검증](../../docs/reviews/2026-10-10-ui-highlight-revalidation.md)은 오선택 미해결을 확인했다. [품질·정답 기준](../../docs/ai-quality-evaluation.md)과 [STT 선정](../../docs/stt-model-selection.md)이 최신 평가 기준이다.

ColorBench·MobiFlow는 검수한 연구용 보조 자료로 사용한다. 우리 선택·완료 정답은 실제 대상 환경의 독립 근거로 구축하며, 자동 라벨과 graph 후보는 정답으로 자동 승격하지 않는다. 공개 자료를 도입해도 AI2의 수집·검수·graph 개선 역할을 유지한다.

기존 문서의 실행 상세와 구현 평가는 AI-Agent(1) 문서로 통합하고, graph learning/proposal은 AI-Agent(2) 문서에서 확장했다. 실제 코드도 `runtime/`, `graph_proposal/`, 공통 `telemetry/`로 분리했다. 오프라인 수집·분석·후보·평가·승인/롤백과 CPU baseline은 구현되어 있으며, [현재 상태](../README.md)에서 실기기 증거·독립 holdout 등 남은 항목을 확인할 수 있다.
