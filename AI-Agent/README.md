# AI-Agent(1) / AI-Agent(2)

모바일 앱에서 사용자 작업을 돕는 AI-Agent(1)과 runtime 데이터로 내부 데이터셋·Task Graph를 만드는 AI-Agent(2)를 관리하는 영역이다.

## 상세 설계 문서

- [전체 구조와 책임 경계](../docs/ai-agent-architecture.md)
- [AI-Agent(1): 사용자 작업 수행 Runtime Agent](../docs/ai-agent-1-runtime.md)
- [AI-Agent(2): Runtime Data / Graph Proposal Agent](../docs/ai-agent-2-graph-proposal.md)

AI-Agent(1)은 text → Typed Goal → 화면 관찰 → guide/action → 결과 검증 → Re-plan을 담당한다. AI-Agent(2)는 전체 runtime 수집·재구성 → success/fail 라벨링 → dataset 생성 → Task Graph 추가·업데이트 후보 → 평가·승인·버전 관리를 담당한다.

문서 분리는 책임과 계약의 분리다. 현재 실행 경로는 `src/ai_agent/runtime/`이며 `learning/*`의 주요 기능은 아직 골격이다. 별도 프로세스나 새 패키지로 코드를 이동한 상태는 아니다.

## 구조

```text
AI-Agent/
  goal_catalog/              # raw 자연어 -> Typed Goal 후보 검색용 catalog
  task_graphs/               # 승인된 versioned Task Graph seed
  data/
    accessibility_trees/     # offline replay/eval용 Accessibility Tree sample
    traces/                  # runtime trace 저장 위치
    proposals/               # Graph Update Proposal 저장 위치
    evaluations/             # Offline Evaluation Report 저장 위치
    training_samples/        # 모델별 학습 sample
    model_artifacts/         # 모델 artifact metadata
  src/ai_agent/
    runtime/                 # 현재 AI-Agent(1) 실행 경로: goal, observe, guide, verify, replan
    goal_typing/             # Goal Typing, slot extraction
    task_graph/              # Graph registry, state manager, versioning
    observation/             # Accessibility parsing, UI normalization, state classify
    matching/                # UI target matching/ranking
    safety/                  # Safety Gate
    guide/                   # Guide Command 생성
    execution/               # 허용된 scroll execution 연동
    verification/            # State transition verification
    replanning/              # Re-plan policy
    learning/                # AI-Agent(2)로 확장할 영역; trace producer는 공통 경계로 분리 예정
    fallback/                # LLM/VLM fallback boundary
    workflows/               # 상위 orchestration 골격, 현재 runtime과 구분
```

## 원칙

- FE는 raw 자연어와 화면 context를 전달한다.
- Agent는 LLM 없이 embedding/rule 기반으로 Typed Goal을 생성한다.
- Agent는 Guide Command를 만들고, FE가 Overlay를 렌더링한다.
- 자동 tap은 금지한다.
- scroll은 target 탐색 목적의 LOW_RISK action으로만 제한적으로 허용한다.
- Task Graph는 runtime에서 즉시 자기 수정하지 않고, trace 기반 proposal 검증 후 version update한다.
- AI-Agent(1)의 실시간 verification과 AI-Agent(2)의 데이터 라벨은 별도로 관리한다.
- step 실패와 최종 task 성공은 동시에 가능하며, 관측이 부족한 결과는 unknown으로 남긴다.
- AI-Agent(2)는 신규 graph, state/transition, selector 등 변경안을 만들고 승인된 artifact만 AI-Agent(1)이 사용한다.
- 현재 MVP-01은 자동 scroll이 비활성이다. Backend의 DB checkpoint와 전체 runtime trace 영속 저장은 별개다.
