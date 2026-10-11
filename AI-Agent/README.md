# AI-Agent(1) / AI-Agent(2)

Accessibility Tree → Vision의 매 사용 동의·FE/BE 계약·개인정보 마스킹과 실패 대응은 [Vision fallback 실행 문서](../docs/ai-agent-vision-fallback.md)를 참고한다.

사용자 작업 실행과 내부 데이터·Task Graph 개선을 패키지와 데이터 폴더로 분리한다.

| 역할 | 코드 | 책임 |
| --- | --- | --- |
| AI-Agent(1) | [`src/ai_agent/runtime/`](src/ai_agent/runtime/README.md) | Backend 선택 Graph·목표·상태 수신 → 정합성 검사 → 화면 해석 → Guide/Action → Verify → Re-plan |
| AI-Agent(2) | [`src/ai_agent/graph_proposal/`](src/ai_agent/graph_proposal/README.md) | 실제 실행 수집·재구성·라벨링, 공개 참고 반입, 독립 검수 dataset·graph 후보·평가·release |
| 공통 관측 계약·진단 | [`src/ai_agent/telemetry/`](src/ai_agent/telemetry/README.md) | trace 생산·개인정보 필터·관측 요약 계약 |
| 공통 Graph 자산 | `task_graph/`, `task_graphs/`, `goal_catalog/` | graph 구조·조회·승인된 입력 자산 |

## 파일 구조

```text
AI-Agent/
  goal_catalog/                    # FE 분류·Backend 지원 매핑의 공유 catalog 자산
  task_graphs/                     # 기존 승인 Graph seed
  data/
    runtime/traces/                # 원천 event 영속화 예정 위치
    fixtures/accessibility_trees/   # 공통 replay fixture
    graph_proposal/                # Agent(2) episode·label·proposal·dataset·평가
  src/ai_agent/
    runtime/                       # Agent(1)의 현재 turn 실행 경로
    graph_proposal/                # Agent(2) 전용 패키지
      ingestion/                   # event 수집 경계
      reconstruction/              # episode·step 재구성 경계
      labeling/                    # 결과 라벨링 경계
      analysis/                    # 실패·성공 경로 분석 경계
      proposals/graph_learner.py
      datasets/dataset_builder.py
      evaluation/graph_validator.py
      models/model_trainer.py
      models/model_registry.py
      releases/versioning.py
      workflows/                   # offline job orchestration 경계
      schemas.py                   # GraphUpdateProposal
    telemetry/
      trace_logger.py              # 공통 진단 producer·filter
      schemas.py                   # RuntimeTrace 관측 요약
    task_graph/                    # 공통 graph 구조·registry
    schemas.py                     # 기존 Agent(1) 컴포넌트·공통 Graph 계약
    goal_typing/                   # 기존 Goal 분류 코드: FE 이관·호출 경로 제거 대상
    observation/                   # Agent(1) 기존 화면 처리 컴포넌트
    matching/                      # Agent(1) 기존 UI matching 컴포넌트
    safety/                        # Agent(1) 기존 Safety 컴포넌트
    guide/                         # Agent(1) 기존 Guide 컴포넌트
    execution/                     # Agent(1) 제한된 실행 경계
    verification/                  # Agent(1) 온라인 결과 검증
    replanning/                    # Agent(1) 세션 내 재계획·기존 StateManager
    fallback/                      # Agent(1) 동의 기반 관측 fallback
    workflows/                     # 기존 사용자 runtime loop 골격
  tests/
    test_agent_boundaries.py        # 두 Agent의 의존성 분리 검증
```

`runtime/*`는 현재 Backend turn API가 사용하는 실행 경로다. 2026-10-10 코드 확인: Backend가 승인 DB graph 본문을 decode하고 episode별 version/hash를 고정·복원하며 adapter에 주입한다. AI1에는 legacy raw Goal Typing·로컬 registry 경로가 남아 있고 typed validator는 글자 확대/축소를 허용한다. DB loader가 미구현이라는 과거 설명은 갱신했다. Backend 충돌 4개로 현재 통합 실행은 차단되어 있다. [UI 재검증](../docs/reviews/2026-10-10-ui-highlight-revalidation.md), [실제 품질·gold 기준](../docs/ai-quality-evaluation.md), [STT 개인정보·한국어 후보](../docs/stt-model-selection.md)를 함께 본다.

## 이번 구조 분리에서 바뀐 점

- `learning/*`의 graph 후보·dataset·평가·모델 모듈을 `graph_proposal/`로 이동했다.
- graph 승격·rollback은 `graph_proposal/releases/versioning.py`로 이동했다.
- 기존 사용자 세션용 StateManager는 `replanning/state_manager.py`로 이동해 공통 graph 영역에서 분리했다.
- `TraceLogger`와 `diagnostic_payload`는 `telemetry/`로 이동하고 runtime·Backend·테스트의 import를 갱신했다.
- `GraphUpdateProposal`은 `graph_proposal/schemas.py`, `RuntimeTrace`는 `telemetry/schemas.py`에만 정의한다.
- 기존 `learning` 패키지와 `task_graph/versioning.py`의 호환용 재export를 남기지 않았다. 내부 호출자는 새 경로를 사용한다.
- [데이터 폴더](data/README.md)는 원천 runtime, 공통 fixture, Agent(2) 파생 산출물로 나눴다.

## 역할 경계와 구현 상태

Agent(1)은 Agent(2)를 import하거나 호출하지 않는다. Agent(2)는 사용자 runtime·Guide·Backend 세션을 직접 실행하지 않고 공통 관측·Graph 계약을 통해 입력을 받는다. 공통 패키지는 어느 쪽 에이전트도 import하지 않는다.

실제 모듈 이동과 import 분리는 완료했지만, graph learner·dataset builder·validator·model trainer·release manager는 골격이다. `labeling/mvp01.py`는 합성 fixture 20건을 처리하는 weak labeler이며, `ingestion`, `reconstruction`, `analysis`, `workflows`의 실제 수집·개선 루프는 다음 단계다. 별도 서버·프로세스도 추가하지 않았다.

온라인 verification은 다음 사용자 안내를 위한 판단이고, Agent(2)의 label은 학습·평가 데이터 판정이다. 둘을 자동으로 동일시하지 않는다. graph proposal은 현재 세션에 직접 적용하지 않고 검증·승인된 version으로만 배포한다.

기존 자동 tap 금지, MVP-01 scroll 비활성, 개인정보 필터·진단 TTL·개수 제한을 유지한다. `TraceLogger`는 메모리 진단용이며 아직 영속 학습 event를 생성하지 않는다.

## 정답 데이터와 구현 우선순위

ColorBench는 검수한 경로·복구 평가의 보조 기준, MobiAgent의 MobiFlow는 작업 의존관계·분기·완료 규칙 참고로 도입한다. 공개 원본·파생본, 합성 fixture, 실제 실행 기록, weak label, 독립 검수한 제품 gold, graph candidate를 구분한다. `final` label 또는 `approved` graph 표시는 그 자체로 데이터 정답 검수의 증거가 아니다.

AI2는 단순 graph 수집에 한정하지 않고 실제 실행 데이터로 근거·라벨·변경안·평가를 생산한다. 개발 순서는 [공통 데이터 계획](../docs/task-graph-data-plan.md)의 P0 평가 계약 → P1 실제 수집 → P2 gold·baseline → P3 후보·회귀 → P4 승인·소비 연결이다. 외부 참고 실험 R1은 병행하며, 모델 학습과 신규 task 확장은 이후로 둔다.

## 상세 설계 문서

- [전체 구조와 책임 경계](../docs/ai-agent-architecture.md)
- [Task Graph 정답 데이터·단계별 개발 계획](../docs/task-graph-data-plan.md)
- [Backend DB Graph 선택·실행 전달](../docs/backend-task-graph-dispatch.md)
- [FE Goal·Command 정의와 전달 형식](../docs/fe-goal-command-contract.md)
- [AI-Agent(1) 상세 설계](../docs/ai-agent-1-runtime.md)
- [AI-Agent(2) 상세 설계](../docs/ai-agent-2-graph-proposal.md)
