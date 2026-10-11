# AI-Agent 데이터 소유권

2026-10-10 평가 단위·독립 검수·split·지표·인수 정책은 [AI 실제 품질·gold 기준](../../docs/ai-quality-evaluation.md)을 따른다. [`evaluation/manifest-template.json`](evaluation/manifest-template.json)은 record 0개의 빈 템플릿이며 제품 gold 수집 완료를 뜻하지 않는다.

정답의 적용 범위와 구축 순서는 [Task Graph 정답 데이터·개발 계획](../../docs/task-graph-data-plan.md)을 따른다. 공개 참고 자료, 합성 fixture, 실제 실행 기록, weak label, 독립 검수한 제품 gold, graph candidate를 별도로 관리한다.

## 현재 폴더와 구현 상태

```text
data/
  runtime/
    traces/                 # Agent(1)/FE/Backend의 원천 event 저장 예정 위치
  fixtures/
    accessibility_trees/    # 양쪽에서 사용하는 검토된 replay fixture
    mvp01_episodes.jsonl     # 합성 예시 20건; 실제 사용자 데이터·제품 gold 아님
  graph_proposal/
    episodes/               # Agent(2)가 재구성한 episode/step
    labels/                 # Agent(2)의 outcome label·근거·revision
    proposals/              # 신규·기존 Task Graph 변경 후보
    evaluations/            # 평가·회귀 보고서
    training_samples/       # 모델별 sample·dataset manifest
    model_artifacts/        # 모델 artifact metadata
```

기존 빈 데이터 폴더를 역할별로 이동했다. `.gitkeep`은 디렉터리 경계를 표시하며 영속 저장 기능이 구현되었다는 뜻은 아니다. 현재 `TraceLogger`는 메모리 진단용이고 위 폴더에 자동으로 쓰지 않는다. `graph_proposal/labels/mvp01_labels.jsonl`은 합성 fixture에 대한 규칙 기반 weak label 출력이며, `label_status=final`도 제품 gold를 의미하지 않는다.

Agent(1)은 Agent(2)의 labels/proposals/evaluations를 직접 수정하지 않는다. Agent(2)는 원천 runtime 데이터를 수정해 과거 실행 결과를 바꾸지 않고 별도 파생 데이터를 만든다. 관측·라벨·dataset·proposal 사이의 연결과 버전을 유지한다.

승인된 입력 자산인 [Task Graph seed](../task_graphs/)와 [Goal Catalog](../goal_catalog/)는 공통 위치를 유지한다. candidate와 운영에 사용할 승인 graph를 같은 파일에 덮어쓰지 않는다. 실제 사용자 데이터 저장에는 별도 개인정보·사용 목적·접근 통제 계약이 필요하다.

## 공개 참고 자료와 제품 정답의 저장 계획

다음은 논리적 분리 계획이며 새 저장소·importer·dataset이 구현됐다는 뜻은 아니다. 실제 저장 backend가 정해져도 분리와 lineage를 유지한다.

| 예정 위치 | 내용 | 포함 조건 |
| --- | --- | --- |
| `external/<source>/<revision>/` | 공개 원본과 source manifest | URL·revision·파일 hash·원본 ID·이용 범위 고정; 원본 보존 |
| `graph_proposal/references/` | 검수·변환한 ColorBench subset/MobiFlow rule | 변환 version·수정/제외 사유·지원 평가 범위 표시 |
| `runtime/traces/` | 실제 실행·통제 시연의 원천 event | 실제 기록 여부, 기기/앱 환경·관측/행동 연결·수집 목적 |
| `graph_proposal/labels/` | 자동/검수 라벨과 revision | outcome·method·review status·근거를 분리 |
| `graph_proposal/gold/selection/` | 우리 승인 catalog 선택 정답 | 후보 snapshot·정책·허용 집합·미지원/모호성·독립 검수 |
| `graph_proposal/gold/paths/` | 실제 확인한 상태·전이·복구 정답 | 여러 유효 경로와 target, 환경별 실제 edge 증거 |
| `graph_proposal/gold/verification/` | step/task 결과 및 판정 불가 정답 | before/action/after·원래 target·값 변화·독립 판정 |
| `graph_proposal/proposals/`, `evaluations/` | graph 후보와 평가 보고서 | base/candidate hash·evidence·holdout·판정 범위 |

폴더 이름만으로 gold를 인정하지 않는다. 표의 gold 경로에는 근거·검수 조건을 만족한 항목만 게시하며 개발/holdout은 manifest로 분리한다. 합성 fixture는 회귀 검사용으로 유지하고 실제 기기 결과 집계에 포함하지 않는다.

## Manifest에 남길 정보

- 출처: `source_kind`(public/device_capture/synthetic), URL·revision·원본 ID·파일 hash 또는 수집 snapshot.
- 적용 범위: task/goal, 기기·OS·앱 버전·locale, 평가 단위와 허용 용도.
- 파생 계보: 변환 코드 version, 원본과 파생 ID 연결, 수정·제외 사유. 없는 행동·값을 합성해 실측 필드로 채우지 않는다.
- 라벨과 검수: `label_method`, `label_status`, `review_status`, `review_revision`, `evidence_refs`, 판정 사유. `final`과 독립 검수 여부를 분리한다.
- 평가: dataset version/hash, split/group ID, 개발/holdout 용도, 분모·제외 정책. 선택 dataset에는 catalog snapshot·selection policy version을 추가한다.

동일 episode·시연·공개 원본의 변환/번역본은 같은 split group으로 묶는다. candidate 생성과 threshold 조정에 사용한 자료를 독립 holdout으로 재사용하지 않는다. runtime verifier의 출력만으로 만든 라벨은 그 verifier의 정확도 평가용 정답이 될 수 없다.

공개 benchmark에서 유효한 reference라도 제품용 gold와는 별도로 보고한다. AI2가 graph 후보를 생성했거나 운영 seed가 등록되었다는 사실도 실제 대상 환경의 정답 검수를 대신하지 않는다.
