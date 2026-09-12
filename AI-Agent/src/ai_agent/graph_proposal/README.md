# AI-Agent(2): Graph Proposal 패키지

전체 runtime 데이터를 재구성·라벨링하고 내부 데이터셋과 Task Graph 추가·업데이트 후보를 만드는 영역이다. 사용자 요청 처리, 화면 안내, 온라인 결과 검증, 세션 Re-plan은 `ai_agent.runtime`의 책임이다.

## 파일 구조와 담당 작업

```text
graph_proposal/
  schemas.py                     # GraphUpdateProposal 계약
  ingestion/                     # event 수집·schema 검사 경계
  reconstruction/                # episode·step·attempt 재구성 경계
  labeling/                      # success/fail/unknown 라벨링 경계
  analysis/                      # 실패 cluster·성공 경로 분석 경계
  proposals/
    graph_learner.py              # graph 후보 생성
  datasets/
    dataset_builder.py            # 모델별 dataset 생성
  evaluation/
    graph_validator.py            # 후보 검증·replay·회귀 평가
  models/
    model_trainer.py              # 후속 모델 학습
    model_registry.py             # 모델 artifact metadata
  releases/
    versioning.py                 # 승인 graph 승격·rollback
  workflows/                     # offline job orchestration 경계
```

`proposals`, `datasets`, `evaluation`, `models`, `releases`에는 기존 모듈을 옮겼다. 새로 구획한 `ingestion`, `reconstruction`, `labeling`, `analysis`, `workflows`는 책임을 명시한 패키지이며 아직 실행 구현이 없다. 현재 learner는 빈 후보, validator는 `NEEDS_DATA`, dataset builder는 빈 sample 구조를 반환한다. 파일 분리는 자동 라벨링·학습·배포 구현 완료를 뜻하지 않는다.

## 의존성 경계

- Agent(2)는 `ai_agent.runtime`, 사용자용 Goal/UI/Guide 모듈, Backend HTTP·세션 저장소를 직접 호출하지 않는다.
- Agent(1)과 Backend의 사용자 응답 경로는 이 패키지를 import하지 않는다.
- 관측 요약 계약은 `ai_agent.telemetry.schemas`, 공통 graph 구조는 `ai_agent.task_graph`를 사용한다.
- `GraphUpdateProposal`은 이 패키지의 `schemas.py`에만 정의한다. 공통 `ai_agent.schemas`에 재정의하거나 runtime으로 export하지 않는다.
- trace 생산·진단 필터는 `ai_agent.telemetry`의 책임이다. Agent(2)가 producer의 개인정보 필터를 우회하거나 직접 사용자 화면을 수집하지 않는다.
- production graph 변경은 candidate 생성·평가·승인 뒤 별도 release로 처리한다. 현재 세션의 graph 객체를 수정하지 않는다.

데이터 출력의 기준 경로는 `AI-Agent/data/graph_proposal/`다. 공통 입력은 `data/runtime/`의 향후 event 데이터와 `data/fixtures/`의 검토된 fixture다. 아직 어떤 모듈도 이 폴더에 자동 저장하지 않는다.

## 다음 구현 순서

1. 영속 event 수집과 개인정보·사용 목적 계약.
2. episode/step/guide/observation 연결 및 누락 검출.
3. 온라인 verification과 별개인 결과 label·근거·revision 생성.
4. label 기반 dataset과 graph 후보 생성.
5. 독립 fixture로 평가한 뒤 승인·release workflow 연결.

상세 라벨·proposal·평가·release 설계는 [AI-Agent(2) 설계 문서](../../../../docs/ai-agent-2-graph-proposal.md)에 둔다.
