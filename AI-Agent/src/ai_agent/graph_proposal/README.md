# AI-Agent 2: Jev 판단과 실행 결과 분석

갱신: 2026-11-09. Jev가 어떤 대상을 얼마나 확신하고 골랐는지, 그 안내 뒤 실제 조작이 성공했는지를 분석합니다. 같은 검수 화면에서 수정 전후도 비교합니다. 기존 대체 경로 순서 변경은 별도의 선택 기능으로 유지합니다. 사용자별 이해도·선호도 프로필과 별도 GPU·LLM은 필요하지 않습니다.

팀 공유용 설명·지표의 분모·검수 방법·실행 명령은 [Jev 평가 안내](../../../../docs/ai2-jev-assessment.md)를 참고합니다. 저장되는 개인 내용과 실행·분석 JSON은 [암호화 저장 절차](../../../../Backend/docs/data-encryption.md)를 따릅니다.

## 폴더 구조와 연결 경계

```text
graph_proposal/
  contracts.py       # 이벤트·manifest 계약과 fingerprint
  schemas.py         # 후보 등 공통 데이터 구조
  graphs.py          # 순수 JSON graph 검사·우선순위 patch
  ingestion/         # runtime 이벤트 수집 계약
  reconstruction/    # episode·step·행동 전후 증거 연결
  labeling/          # 작업별 라벨 판정
  outcomes.py        # 단계 결과 판정
  analysis/          # 실패 유형·분모·추가 수집 요청
  datasets/          # manifest 검수와 train/validation/holdout 분리
  proposals/         # 기존 대체 전이 우선순위 후보 생성
  evaluation/        # 구조·출처·독립 replay·회귀 평가
  releases/          # 검토·승인·version·rollback 계약
  models/            # CPU 빈도 baseline·로컬 artifact registry
  workflows/         # 위 단계를 연결하는 오프라인 pipeline
```

DB·사용자 동의·삭제 연계와 실제 AI1 replay adapter는 `Backend/scripts/agent2.py`, release 영속화는 `Backend/app/repositories/graph_releases.py`가 담당합니다. 합성 입력은 `AI-Agent/data/fixtures/agent2/`, 로컬 산출물은 Git에서 제외한 `AI-Agent/data/local/`에 둡니다. 모델 학습과 graph 후보 생성은 서로 다른 단계입니다.

탐색 그래프의 DB 생성은 `Backend/scripts/browse_graph_learning.py`, 온라인 응답 이후의 별도 프로세스 실행·재처리는 `Backend/app/services/browse_graph_jobs.py`가 담당합니다. 사용자 실행 프로세스는 Agent 2 모듈을 import하지 않습니다.

## 구현된 범위

| 단계 | 구현 |
| --- | --- |
| 수집 | Backend의 영속 runtime_events를 최신 model_training 동의 이후·미만료 범위에서 처리 |
| 재구성 | 이벤트 ID/sequence 충돌·중복·누락·episode 내 graph 변경 검사, 이전 guide·행동·전후 관측 연결 |
| 라벨·분석 | 시도 성공/실패·대기·수동 진행·불확실 분리, 중복 시도 제외, 실패 cluster·분모·추가 수집 요청 |
| 데이터 | 출처·revision·검수·근거 manifest 검사, train/validation/holdout 및 group/session 분리 |
| 후보 생성 | 기존 대체 전이 우선순위 patch 및 실제 조작으로 연결된 공개 탐색의 신규 그래프 초안 생성 |
| 평가 | base/candidate 구조·hash 검사, 동일 관측의 실제 AI1 replay, holdout 누출·회귀·데이터 부족·도구 오류 구분 |
| 승인·반영 | 정확한 평가·본문 hash에 검토자/시각 결합, DB 활성 version과 audit 원자 반영, stale-base 차단·rollback |
| 모델 | 명시적으로 weak label을 허용한 train split의 CPU 빈도 baseline, 불변 로컬 artifact registry |

같은 pending guide의 대기 관측이 여러 번 들어와도 조작 전 원래 기준 관측을
보존합니다. 결과는 해당 guide에서 실제 관측된 before hash와 연결하며, 대기 polling은
시도 분모에 넣지 않습니다. 알려지지 않은 before hash와 변경된 target binding은
기존처럼 증거 부족·계약 오류로 처리합니다.

실제 제품 gold·독립 holdout은 아직 없습니다. 합성·공개 참고 자료만으로 평가 PASS나 운영 release를 만들지 않습니다. 새 selector·UI 경로·작업을 추측 생성하지 않습니다. `proposals/browse_graph.py`는 모든 관측 변화가 조작으로 연결되고 목적 화면까지 검증된 탐색에서 새 상태·전이 그래프를 구성합니다. Backend는 완료 응답 이후 및 주기적 재처리로 `graph_proposals`에 암호화 저장하며, 원천 기록·동의·만료에 함께 삭제합니다. 현재 대상과 명령은 [쿠팡 탐색 그래프 절차](../../../../docs/coupang-browse.md#ai2-신규-그래프-자동-생성)를 따릅니다. 생성 초안은 독립 검증·승인 전에는 운영 경로로 활성화하지 않습니다.

## DB 기록 처리

Backend 디렉터리에서 DATABASE_URL을 설정하고 실행합니다.

```powershell
python scripts/agent2.py process --limit 100
python scripts/agent2.py learn-browse --limit 100
python scripts/agent2.py list-browse-graphs --limit 100
python scripts/agent2.py assess-jev --limit 100
python scripts/agent2.py assess-jev --reviews target-reviews.json --format json
python scripts/agent2.py audit
python scripts/agent2.py propose-routes --manifest review-manifest.json --cases device-holdout.json
python scripts/maintenance.py
```

process는 세션별 재구성·분석 결과를 원천 event에 연결된 DB receipt로 저장합니다. 같은 입력은 중복 처리하지 않고, 최신 세션 결과 하나를 유지합니다. 원문 화면·목표·토큰을 출력하거나 사용자 데이터를 파일로 반출하지 않습니다. 동의 전 기록을 소급 사용하지 않으며 철회·탈퇴·세션 삭제·가장 오래된 근거 만료 시 결과를 삭제합니다.

audit는 `structural_valid`(구조·참조 무결성), `static_valid`(현재 runtime 실행 조건 포함), `execution_enabled`(승인·활성·검사 통과)를 구분합니다. 미지원 완료 predicate와 담당 영역을 별도 보고하며 `device_validation`은 항상 `NOT_ASSESSED`입니다. 초안의 미지원 기능을 DB 손상과 혼동하지 않습니다.

`catalog_coverage`는 카탈로그의 승인 목표를 실제 Backend 선택기로 조회합니다. 현재 v2.4.0은 설정 110개와 쿠팡 71개, 총 181개입니다. 쿠팡의 화면 이동·확인 후 조작·직접 진행 안내는 [실행 문서](../../../../docs/tasks/coupang.md)를 따르며 실기기 검수는 별도입니다. 그래프 누락·비활성·승인 해제는 `MISSING_GRAPH`, 같은 최상위 우선순위 중복은 `AMBIGUOUS_GRAPH`, 본문/해시 손상은 `INVALID_GRAPH`, 카탈로그와 슬롯·작업 종류가 다르면 `GRAPH_CONTRACT_MISMATCH`로 표시합니다. 승인 목표 밖의 활성·승인 그래프도 별도 보고합니다. 여러 release version은 목표 하나로 집계하며 선택된 version/hash를 보여줍니다.

승인 graph 결함이나 목표 누락·불일치가 있으면 `status=BLOCKED`, 종료 코드 1입니다. 모두 통과하면 `status=PASS`, 종료 코드 0입니다. 감사는 누락된 그래프를 생성하거나 활성화하지 않으므로 배포 후 DB 반영 확인에도 사용할 수 있습니다. PASS는 정적 구성·선택 검사 결과이며 실기기 실행 성공 판정이 아닙니다.

`propose-routes`는 동의 이후의 여러 세션을 공통 그래프 기준으로 비교하고, 검수 manifest에 선언된 학습 기록만으로 기존 전이의 우선순위를 제안합니다. 성공률이 같으면 충분한 성공 시도의 소요 시간을 비교하며, 기종 그룹에서 관측한 성공률이 악화되면 제안을 보류합니다. 개인 식별정보 대신 허용된 제조사·Android 주 버전 그룹만 분석합니다. 새 경로는 추측 생성하지 않습니다.

운영 DB 분석은 split/독립 검수가 아직 없는 기록을 자동 학습·배포하지 않습니다. 후보 생성·평가는 별도로 검수한 데이터셋과 manifest를 준비한 뒤 실행합니다. audit는 DB에 등록된 graph 전부를 검사하며 초안의 미지원 predicate와 승인 graph 결함을 구분합니다. 구조 통과는 실기기 통과가 아닙니다. DB 변경, 앱 동의, `publish --run-id`와 Jev 우선순위 반영은 [운영 절차](../../../../Backend/docs/shared-graph-improvement.md)를 참고합니다.

## 데이터 없이 실행하는 합성 예제

다음 fixture는 사람이 만든 8개 이벤트·4개 episode이며 실제 사용자/실기기 자료가 아닙니다.

```powershell
python scripts/agent2.py fixture --events ../AI-Agent/data/fixtures/agent2/events.jsonl --graphs ../AI-Agent/data/fixtures/agent2/graphs.json --manifest ../AI-Agent/data/fixtures/agent2/manifest.json --now 200 --output ../AI-Agent/data/local/example.json
python scripts/agent2.py train-baseline --run ../AI-Agent/data/local/example.json --registry ../AI-Agent/data/local/models --allow-weak
```

첫 명령은 기존 대체 경로 우선순위 후보 1개를 만들고 NEEDS_DATA를 반환합니다. 독립 기기 holdout이 없기 때문입니다. 두 번째는 테스트용 CPU 빈도 baseline이며 운영 모델을 활성화하지 않습니다. 파일 산출물은 새 경로만 허용하고 모델 artifact는 같은 ID의 다른 내용을 덮어쓰지 않습니다. 로컬 산출물은 Git에서 제외됩니다.

## 데이터 계약·검수

[events](../../../data/fixtures/agent2/events.jsonl), [graph](../../../data/fixtures/agent2/graphs.json), [manifest](../../../data/fixtures/agent2/manifest.json)가 실행 가능한 예제입니다.

- event는 ai2-event.v1이며 session/episode/sequence·시각·고정 graph version/hash·구조적 runtime 증거를 포함합니다. 임의 텍스트 필드는 거절합니다.
- manifest는 ai2-dataset.v1이며 dataset/source revision, episode/group, split, source_kind, review_status, reviewer/review_revision, evidence_refs, label/label_method를 포함합니다.
- weak label이나 episode 결과 검수가 개별 단계의 독립 정답이 되지는 않습니다. 사용자 만족도 기술 성공과 분리합니다.
- 같은 안내 ID로 대기를 관측해도 최초 안내의 상태·대상·기준 화면과 값은 보존합니다. 이후 관측은 앞뒤 해시가 연결될 때만 근거에 추가하며, 결과가 최초 화면이나 연결된 대기 화면을 참조하면 원래 기준과 비교합니다. 대기는 성공률의 시도 수에 포함하지 않습니다.
- 분석 결과와 DB 재처리 키에는 `reconstruction_policy`를 기록합니다. 이전 정책의 저장 결과는 원본 이벤트가 남아 있고 동의·보존 조건을 충족하면 다음 `process` 실행에서 다시 계산합니다.
- 같은 group 또는 세션을 train과 holdout에 나누지 않습니다. holdout은 후보 생성과 baseline fitting에 사용하지 않습니다.
- 실제 신규 경로를 제안하려면 기기에서 그 행동의 결과를 확인해야 합니다. 다른 행동을 선택한 candidate에 과거 행동 이후 화면을 재사용하지 않습니다.

## Replay·승인·롤백

검수한 replay case는 episode_id, task_id, source_kind, base graph_hash, review_revision, evidence_refs와 replay 본문을 담습니다. replay는 ai1-recovery-replay.v1의 turns/observation/독립 expected를 사용합니다. manifest의 holdout·검수 revision·근거와 일치해야 합니다. 행동이 있는 turn은 실제 captured target과 이전 guide 연결이 필요합니다.

GraphValidator는 runtime adapter와 graph loader를 주입받습니다. 실제 AI1을 호출하는 adapter는 [Backend CLI](../../../../Backend/scripts/agent2.py)에 있어 Agent 2가 Agent 1을 직접 import하지 않습니다. 결과는 PASS/REJECT/NEEDS_DATA/ERROR이며 데이터 부족을 성공으로 간주하지 않습니다. PASS는 선언된 사례의 판정일 뿐 전체 사용자 성공률이 아닙니다.

Backend replay는 `RuntimeAgentAdapter`로 엔진을 생성하므로 평가 프로세스의
`AI1_UI_SELECTOR_PROVIDER` 설정을 그대로 사용합니다. 운영과 같은 환경변수로 실행해야
하며 `jev` 구성의 평가는 실제 provider를 호출할 수 있습니다. 네트워크 없는 회귀에서는
동일 adapter에 모의 Jev 전송만 주입합니다. 기본 라이브러리 replay는 별도의 규칙 baseline입니다.
평가 결과의 `runtime_configuration`에는 provider·코드 해시와 Jev 모델·신뢰도 기준을
기록합니다. baseline/candidate 설정이 다르면 평가 오류이며, Jev 게시 시에는 현재 설정과
동일한 평가만 허용합니다. 규칙 평가나 이전 모델·코드·기준값의 PASS로 게시할 수 없습니다.

pipeline은 후보의 task에 맞는 holdout을 전달합니다. 실행 집계 상태는 평가 도구 오류가 있으면 ERROR, 그렇지 않고 PASS 후보가 있으면 READY_FOR_REVIEW, 평가가 없거나 추가 증거가 필요하면 NEEDS_DATA, 나머지는 REJECTED입니다. READY_FOR_REVIEW도 개별 후보의 승인 여부를 대신하지 않습니다. fixture CLI는 ERROR/REJECTED에서 종료 코드 1을 반환하며, NEEDS_DATA는 성공 판정이 아니라 정상적인 데이터 부족 보고입니다.

검수 자료를 준비한 운영자가 실행할 명령입니다. publish는 저장된 PASS를 그대로 믿지 않고 replay를 다시 실행합니다.

```powershell
python scripts/agent2.py publish --proposal proposal.json --base base.json --manifest manifest.json --cases cases.json --version 1.1.0 --reviewer reviewer-id
python scripts/agent2.py rollback --task MVP-01 --version 1.0.0 --expected-current-hash CURRENT_SHA256 --reviewer reviewer-id
```

진행 중 episode는 기존 version/hash를 유지합니다. 승인·rollback은 새 선택에 적용됩니다. 실제 운영 배포는 이번 로컬 구현에서 실행하지 않았습니다. 별도 curated 파일/배포 모델의 철회·폐기 정책은 DB cascade와 별도로 관리해야 합니다.

설계 기준: [제품 정답 데이터 계획](../../../../docs/task-graph-data-plan.md), [품질 평가](../../../../docs/ai-quality-evaluation.md), [개인정보](../../../../Backend/docs/README.md#개인정보).
