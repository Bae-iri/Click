# AI-Agent(2): Graph Proposal 패키지

2026-10-10: [실제 품질·정답 데이터 기준](../../../../docs/ai-quality-evaluation.md)에서 독립 검수·holdout·승격 조건을 정의했다. `outcomes.py`는 기술 결과와 사용자 응답을 분리 집계하는 오프라인 함수이며 영속 수집·자동 학습 pipeline은 아니다.

전체 runtime 데이터를 재구성·라벨링하고 내부 데이터셋과 Task Graph 추가·업데이트 후보를 만드는 영역이다. AI2는 승인된 graph의 새 version을 DB에 반영하는 workflow를 맡고, Backend는 사용자 요구에 맞는 DB graph를 선택·전달한다. 전달 graph의 화면 판단·안내·온라인 검증·세션 내부 Re-plan은 `ai_agent.runtime`의 책임이다.

ColorBench는 검수한 경로·복구 평가 보조, MobiAgent의 MobiFlow는 의존관계·완료 규칙의 설계 참고로 사용한다. AI2는 실제 실행·통제 시연을 수집해 제품 정답의 검수 자료를 만들고 graph를 제안한다. 자동 라벨·공개 reference·candidate를 제품 gold와 구분한다. [정답 데이터·개발 계획](../../../../docs/task-graph-data-plan.md)이 채택 범위와 구현 순서의 기준이다.

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

`proposals`, `datasets`, `evaluation`, `models`, `releases`에는 기존 모듈을 옮겼다. `ingestion`, `reconstruction`, `analysis`, `workflows`의 실제 수집·분석 pipeline은 미구현이다. `labeling/mvp01.py`에는 아래 합성 fixture용 weak labeler가 있다. learner는 빈 후보, validator는 `NEEDS_DATA`, dataset builder는 빈 sample 구조를 반환한다. 파일 분리는 제품 gold·graph 학습·배포 구현 완료를 뜻하지 않는다.

## 의존성 경계

- Agent(2)는 `ai_agent.runtime`, 사용자용 Goal/UI/Guide 모듈, Backend HTTP·세션 저장소를 직접 호출하지 않는다.
- Agent(1)과 Backend의 사용자 응답 경로는 이 패키지를 import하지 않는다.
- 관측 요약 계약은 `ai_agent.telemetry.schemas`, 공통 graph 구조는 `ai_agent.task_graph`를 사용한다.
- `GraphUpdateProposal`은 이 패키지의 `schemas.py`에만 정의한다. 공통 `ai_agent.schemas`에 재정의하거나 runtime으로 export하지 않는다.
- trace 생산·진단 필터는 `ai_agent.telemetry`의 책임이다. Agent(2)가 producer의 개인정보 필터를 우회하거나 직접 사용자 화면을 수집하지 않는다.
- production graph 변경은 candidate 생성·평가·승인 뒤 별도 release로 처리한다. 현재 세션의 graph 객체를 수정하지 않는다.

데이터 출력의 기준 경로는 `AI-Agent/data/graph_proposal/`다. 공통 입력은 `data/runtime/`의 향후 event 데이터와 `data/fixtures/`의 검토된 fixture다. 현재는 아래 명령을 실행할 때만 label 파일을 저장한다.

## MVP-01 라벨링 시작하기

`data/fixtures/mvp01_episodes.jsonl`은 실제 사용자 기록이 아닌 **합성 예시 20건**이다. 한 줄에 episode 하나를 기록한다. `steps`의 `action_observed`, 전후 `observation_id`, 예상·관측 state가 모두 있어야 단계 success/fail을 판정한다. 최종 작업은 `completion_evidence.source=accessibility_range`와 서로 다른 전후 관측 ID, `before_font_scale`/`after_font_scale` 수치가 있을 때만 success/fail로 판정한다. 정보가 모자라면 unknown이다. 중간 단계 실패 후 최종 작업 성공도 가능하다.

```powershell
cd Click/AI-Agent
$env:PYTHONPATH = "src"
python -m ai_agent.graph_proposal.labeling.mvp01 data/fixtures/mvp01_episodes.jsonl data/graph_proposal/labels/mvp01_labels.jsonl
```

출력은 라벨, 근거 관측 ID, 이유, 정책 버전을 담은 JSONL이다. 이는 규칙 기반 **weak label**이며 `label_status=final`이어도 제품 gold가 아니다. 현재 `TraceLogger`의 진단 기록은 제한된 correlation hash만 보관하고 라벨러에 필요한 실제 episode/step·완료 증거를 제공하지 않으므로 이 입력 형식으로 바로 변환할 수 없다. 실제 기록 연동에는 영속 event 계약이 필요하며 기존 개인정보·사용 목적 범위를 따른다.

## 다음 구현 순서

1. P0: 선택·경로·완료 정답 단위와 source/review/split manifest를 정의한다.
2. P1: 실제 기기 시연·event 수집, episode/step/guide/observation 연결과 누락 검출을 구현한다.
3. P2: weak label을 독립 근거로 검수해 제품 gold·holdout을 만들고 현재 선택기·verifier baseline을 기록한다.
4. R1 병행: ColorBench subset·MobiFlow rule을 검수하고 적용 범위가 제한된 참고 실험을 만든다.
5. P3: 실제 실패에서 graph 후보를 생성하고 정적 검사·실행 근거·독립 holdout 회귀로 평가한다.
6. P4/P5: 승인·release·Backend 선택·AI1 소비를 연결한 뒤 신규 task/환경으로 확장한다.

공개 benchmark 통과만으로 제품 적용 `PASS`를 반환하지 않는다. 원천 증거가 부족하면 `NEEDS_DATA`, 파싱·평가 도구 오류는 `ERROR`로 구분하는 validator를 구현한다. 실제 수집과 제품 gold 구축은 현재 미완료다.

상세 라벨·proposal·평가·release 설계는 [AI-Agent(2) 설계 문서](../../../../docs/ai-agent-2-graph-proposal.md)에 둔다.
