# AI-Agent 데이터 소유권

```text
data/
  runtime/
    traces/                 # Agent(1)/FE/Backend의 원천 event 저장 예정 위치
  fixtures/
    accessibility_trees/    # 양쪽에서 사용하는 검토된 replay fixture
  graph_proposal/
    episodes/               # Agent(2)가 재구성한 episode/step
    labels/                 # Agent(2)의 outcome label·근거·revision
    proposals/              # 신규·기존 Task Graph 변경 후보
    evaluations/            # 평가·회귀 보고서
    training_samples/       # 모델별 sample·dataset manifest
    model_artifacts/        # 모델 artifact metadata
```

기존 빈 데이터 폴더를 역할별로 이동했다. `.gitkeep`은 디렉터리 경계를 표시하며 영속 저장 기능이 구현되었다는 뜻은 아니다. 현재 `TraceLogger`는 메모리 진단용이고 위 폴더에 자동으로 쓰지 않는다.

Agent(1)은 Agent(2)의 labels/proposals/evaluations를 직접 수정하지 않는다. Agent(2)는 원천 runtime 데이터를 수정해 과거 실행 결과를 바꾸지 않고 별도 파생 데이터를 만든다. 관측·라벨·dataset·proposal 사이의 연결과 버전을 유지한다.

승인된 입력 자산인 [Task Graph seed](../task_graphs/)와 [Goal Catalog](../goal_catalog/)는 공통 위치를 유지한다. candidate와 운영에 사용할 승인 graph를 같은 파일에 덮어쓰지 않는다. 실제 사용자 데이터 저장에는 별도 개인정보·사용 목적·접근 통제 계약이 필요하다.
