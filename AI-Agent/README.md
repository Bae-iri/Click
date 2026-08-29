# AI Agent

모바일 앱 사용 지원 AI Agent의 런타임, Task Graph, 학습/업데이트 파이프라인을 담는 영역이다.

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
    goal_typing/             # Goal Typing, slot extraction
    task_graph/              # Graph registry, state manager, versioning
    observation/             # Accessibility parsing, UI normalization, state classify
    matching/                # UI target matching/ranking
    safety/                  # Safety Gate
    guide/                   # Guide Command 생성
    execution/               # 허용된 scroll execution 연동
    verification/            # State transition verification
    replanning/              # Re-plan policy
    learning/                # Trace -> graph proposal/model training pipeline
    fallback/                # LLM/VLM fallback boundary
    workflows/               # end-to-end runtime orchestration
```

## 원칙

- FE는 raw 자연어와 화면 context를 전달한다.
- Agent는 LLM 없이 embedding/rule 기반으로 Typed Goal을 생성한다.
- Agent는 Guide Command를 만들고, FE가 Overlay를 렌더링한다.
- 자동 tap은 금지한다.
- scroll은 target 탐색 목적의 LOW_RISK action으로만 제한적으로 허용한다.
- Task Graph는 runtime에서 즉시 자기 수정하지 않고, trace 기반 proposal 검증 후 version update한다.
