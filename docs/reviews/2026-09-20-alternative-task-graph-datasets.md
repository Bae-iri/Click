# 기존 네 자료 외의 Task Graph 정답 후보 재조사

확인일: 2026-09-20. AndroidWorld·AITW·AndroidControl·PixelHelp 외에 공식 논문, 저자 저장소, 실제 배포 파일을 조사했다.

**후속 재재검증:** [선택·검증 단계별 적합성 감사](2026-09-20-task-graph-gold-fitness-audit.md)에서 공개 평가 함수를 직접 실행했다. 아래 후보 우선순위는 우리 graph 선택 gold를 확보했다는 뜻이 아니다. ColorBench는 공유 UI 환경, MobiFlow는 DAG 검증 규칙으로 범위를 제한한다. 실제 적용 판단은 후속 감사의 판정을 따른다.

**다른 후보는 있다. 특히 ColorBench는 화면 전이 graph와 작업별 milestone을, MobiAgent의 MobiFlow는 작업별 의존관계·분기·성공 조건을 공개한다. 기존 네 자료만 조사해서 공개 graph 정답 자체가 없다고 일반화해서는 안 된다.** 다만 외부 benchmark 내의 참조 graph와 우리 삼성 설정·쿠팡·정부24 제품의 정답 graph는 적용 범위가 다르다.

이 문서의 우선순위는 데이터 구조와 현재 공개 상태에 근거한 연구 후보 선정이다. Click에 연결한 실험 결과나 모델 성능 비교가 아니다.

## 판정 기준

| 정답의 종류 | 노드 | 간선 | 평가할 수 있는 것 |
| --- | --- | --- | --- |
| UI 전이 graph | 관측한 화면/상태 | 행동으로 인한 화면 전이 | 경로 탐색, 재계획, backtracking, graph 내 목표 도달 |
| 작업 의존관계 DAG | 완료해야 할 단계/조건 | 선행조건 또는 허용 분기 | 단계 순서, 누락, AND/OR 제약 |
| 작업별 시연 | 실행 중 관측 | 실제 실행 행동 | 관찰된 경로 및 대상의 일치 |
| 도구 호출 graph | API/tool 호출 | 데이터·순서 의존성 | 요청 분해, 도구 선택, 의존관계 예측 |

UI 전이 graph에는 뒤로 가기 등의 순환이 있을 수 있다. 모든 자료를 하나의 DAG로 변환하면 정보가 손실된다. 탐색 UTG의 관측 간선도 전제조건·실패 분기·최종 성공까지 검수한 task graph와 같지는 않다.

## 실제 다운로드·구조 검사 범위

[실행 스크립트](evidence/audit_alternative_task_graphs.py)와 [결과 JSON](evidence/alternative-task-graph-audit.json)에 URL, commit/revision, SHA-256, 파일별 결과를 기록했다. JSON/YAML을 파싱하고 노드·간선·참조·순환을 검사했다. 외부 agent, LLM API, 기기 행동은 실행하지 않았다.

| 후보 | 이번에 직접 확인한 파일 | 확인 수준 |
| --- | --- | --- |
| ColorBench | 공식 `data/graph.json`, `data/tasks.json`, 전이·결과 평가 코드 | graph 전체와 task 정의 구조 검사. 화면 ZIP과 실행 환경은 미검증 |
| MobiAgent / MobiFlow | `task_rules/`의 YAML 59개, config, loader, DAG/verifier/condition 코드 | 모든 공개 rule YAML 파싱 시도 및 참조 검사. OCR/LLM 판정 정확도 미검증 |
| DroidTask / AutoDroid | 공식 저장소의 calendar `utg.yaml` | 72개 탐색 record 파싱. Drive의 전체 정답 task/state archive는 미다운로드 |
| Mobile3M + Mobile-Bench-v2 | 배포 파일 목록, README, simple 600건·complex 200건 샘플 JSON | task 샘플 파싱. 대용량 Mobile3M graph archive는 미다운로드·미결합 |
| Microsoft TaskBench | DailyLife APIs `data.json`, `graph_desc.json` | JSONL 4,318건에서 `task_nodes`, `task_links` 확인. API 실제 실행은 미검증 |
| CaptainCook4D / DTGL | 공식 코드 저장소의 `ground_truth_task_graphs/*.json` 24개 | 24개 전체 graph 구조 검사. 영상·의미적 주석의 정확도 재검수는 미실시 |
| 2026 MobiFlow / MobiBench | 공식 논문, 저장소 tree, `env/fsm.py`, 데이터 README와 LFS 포인터 | 코드·배포 메타데이터만 확인. 704,860,742-byte archive 본문은 미다운로드 |

## 1. ColorBench — Android 경로 평가에 가장 직접적인 신규 후보

[논문](https://arxiv.org/html/2510.14621v1)과 [공식 저장소](https://github.com/MadeAgents/ColorBench)는 21개 앱의 175개 복합 작업, 복수 정답 경로·오류 경로·backtracking을 지원하는 화면 전이 graph를 설명한다. 논문은 시연 수집 및 모델 기반 행동 보완/graph 병합 후 사람 검수를 했다고 보고한다. 따라서 기존 단일 경로 시연보다 우리 graph 기반 경로·복구 평가에 직접적이다. 사람이 검수했다는 사실은 저자의 보고이며, 이번 검사에서 화면 의미를 전부 다시 검수한 것은 아니다.

고정 commit: `1f26a84230d26f99d84eab97d05032582b39018e`.

- [graph.json](https://github.com/MadeAgents/ColorBench/blob/1f26a84230d26f99d84eab97d05032582b39018e/data/graph.json)은 `source screenshot → destination screenshot → action annotations` 구조다. source entry는 1,960개이며 destination-only 27개를 포함한 **서로 다른 화면 식별자는 1,987개**, 방향 간선 쌍은 3,154개다. destination-only entry는 이 형식에서 암묵적 종단 노드로 취급할 수 있으므로 그것만으로 오류라고 판단하지 않았다.
- [tasks.json](https://github.com/MadeAgents/ColorBench/blob/1f26a84230d26f99d84eab97d05032582b39018e/data/tasks.json)은 목표, 최적 단계 수, 앱 수, milestone의 화면 ID를 연결한다. 단순히 screenshot만 공개한 자료가 아니다.
- 현재 `tasks.json`의 task 7에서 `optimal_steps: 14` 뒤 쉼표가 빠져, 원본 그대로 `json.loads`를 호출하면 **181행에서 실패**한다. 분석용 메모리 복사본에 쉼표 하나를 넣었을 때 175개 task와 588개 milestone을 읽었다. 이 수치는 해당 문법 보정 후의 검사이며 원본은 변경하지 않았다.
- 보정 후 task 중 13개에는 graph의 source/destination 전체 집합 어디에도 없는 milestone ID가 있었다. 4개 milestone은 `page_node` 대신 별도 답변 형식을 사용한다. 405개 간선 쌍의 행동 annotation 배열이 비어 있어 그것들을 모두 실행 가능한 UI 행동으로 간주하지 않았다. 이 항목들의 의도·실행 시 처리는 추가 확인이 필요하다.
- [결과 평가 코드](https://github.com/MadeAgents/ColorBench/blob/1f26a84230d26f99d84eab97d05032582b39018e/src/test/parse_result.py)는 trajectory의 화면 ID 집합에 milestone 화면이 등장하는지 검사한다. 이 규칙은 Click의 사용자 이벤트 상관관계나 시스템 설정 전후 값 검증을 대신하지 않는다.

**권고:** 공개 graph를 이용한 경로 탐색·실패 복구 평가의 첫 후보로 선정하되, 문법과 task/graph 참조를 검수한 부분집합부터 사용한다. 보조적인 화면 graph 정답으로 활용 가능하지만 삼성/한국어·쿠팡·정부24의 정답이 확보된 것은 아니다. 전체 benchmark 실행과 이미지 ZIP 정합성을 확인하기 전 채택 완료라고 부르지 않는다.

## 2. MobiAgent의 MobiFlow — 명시적인 작업 DAG·성공 조건

[공식 하위 프로젝트](https://github.com/IPADS-SAI/MobiAgent/tree/main/MobiFlow)는 작업 단계를 `nodes`, AND 선행조건을 `deps`, OR 후속 분기를 `next`, 완료 조건을 `success.any_of/all_of`로 표현한다. 화면 전이 graph 전체보다 **어떤 단계를 어떤 제약하에 완료해야 하는가**에 대한 참조 정의다.

고정 commit: `4ee794021bb34a8de4d00c52ca8fa6f0065a45ff`.

- `task_rules/`에 있는 **59개 YAML**을 전부 읽었다. 여기에는 예시와 variant도 있으므로 59개 고유 사용자 task라고 표현하지 않는다.
- 57개가 파싱되었으며 총 222개 단계 노드를 담았다. 파싱된 정의 내 노드의 간선은 모두 비순환이었다. 5개 규칙에서 OR 분기, 1개에서 여러 AND 선행조건을 확인했다.
- `feizhu-type7.yaml`, `xiecheng_jd-type7.yaml`은 YAML 들여쓰기 오류로 파싱되지 않았다. `ele-type3.yaml`은 정의되지 않은 선행 노드, `gaode-type1.yaml`은 정의되지 않은 성공 노드를 참조했다.
- `bilibili-type2.yaml`의 한 condition은 `llm`을 `params` 밖에 두었는데, 공개 loader는 `type`, `params`만 읽는다. 해당 조건의 의도가 파서에 전달되는지 확인이 필요하다.
- 파싱한 condition은 `escalate`·`juxtaposition` 형태로 OCR/LLM 등의 판정을 사용한다. 따라서 DAG 규칙이 명시되어 있다는 사실과 관측을 정확하게 판단하는 oracle이 있다는 사실은 구분해야 한다. 일부 점수 필드도 존재하므로 전체 프레임워크를 점수 없는 기법으로 소개하지 않는다.

예: [Taobao 검색→상품 열기→장바구니 작업 규칙](https://github.com/IPADS-SAI/MobiAgent/blob/4ee794021bb34a8de4d00c52ca8fa6f0065a45ff/MobiFlow/task_rules/taobao/type2-taobao-search-open-add_cart.yaml).

**권고:** 우리 task graph의 전제조건·분기·완료 규칙 스키마 및 오프라인 trace 검증 설계에 활용할 수 있다. 공개 rule을 전부 검수된 gold label로 승격하지 말고, 유효한 rule과 자체 판정 증거를 연결한다. 중국 앱의 rule을 쿠팡 등 다른 앱의 정답으로 재명명하면 안 된다.

## 3. DroidTask — 정답 task 시연과 탐색 UTG를 함께 제공

[저자 저장소](https://github.com/MobileLLM/AutoDroid/tree/newbranch)는 `task*.yaml`의 정답 시연, `utg.yaml`의 탐색 기록, screenshot/view hierarchy, `state_str` 연결 방식을 문서화한다. [AutoDroid 논문](https://www.microsoft.com/en-us/research/wp-content/uploads/2024/07/mobicom24-autodroid.pdf)은 13개 앱의 158개 task를 설명한다.

고정 commit: `839b824774173d8e6373b48fc7b82f55f3bf77ae`. 실제 [calendar UTG](https://github.com/MobileLLM/AutoDroid/blob/839b824774173d8e6373b48fc7b82f55f3bf77ae/memory/utgs/calendar/utg.yaml)를 safe YAML parser로 읽어 72개 record에서 `State`, `Choice`, `Input`, `state_str`, `new_state_str`를 확인했다.

**권고:** 작은 Android graph 추출·탐색 실험에 적합한 후보다. 다만 이번에 실제로 파싱한 것은 탐색 UTG 하나이며, Drive archive의 158개 정답 task 전체를 검증하지 않았다. 무작위 탐색 기록의 모든 간선이 작업별 의미·완료 조건까지 검수된 gold라는 뜻도 아니다.

## 4. Mobile3M + Mobile-Bench-v2 — 대규모 graph와 다중 경로 작업

[Mobile3M 원 논문](https://arxiv.org/html/2409.14818v1)은 중국 앱 49개에서 관찰한 화면과 UI 행동을 방향 graph로 구성했다고 설명한다. [배포 저장소](https://huggingface.co/datasets/xwk123/Mobile3M/tree/main)에는 앱별 graph archive가 있다. 이번에는 목록과 README만 확인했고, 전체 49개 앱 데이터가 빠짐없이 배포되었는지 또는 archive 내부 graph가 완전한지는 검증하지 않았다.

[Mobile-Bench-v2](https://huggingface.co/datasets/xwk123/MobileBench-v2)는 Mobile3M과 결합하는 common-simple/common-complex 작업을 제공하며 [논문](https://arxiv.org/abs/2505.11891)은 다중 경로 평가를 설명한다. 실제 sample 파일은 각각 600건, 200건이며 `name`, `task`, `type` 필드를 갖는다. **샘플 JSON만 받으면 간선·상태·완료 조건까지 확보되는 형태는 아니다.** Mobile3M에 대한 경로 연결과 다중 경로 evaluator를 추가 확인해야 한다.

두 데이터 README는 데이터에 **CC BY-NC-SA 4.0**, 코드에 Apache 2.0을 명시한다. 상단 Apache 배지만 보고 데이터도 같은 조건이라고 해석하지 않는다. [Mobile3M 조건](https://huggingface.co/datasets/xwk123/Mobile3M#1-license), [Mobile-Bench-v2 조건](https://huggingface.co/datasets/xwk123/MobileBench-v2#license)

**권고:** 대규모 UI graph 연구의 후속 후보. 현재 작은 MVP 정답 확보의 첫 선택으로 삼을 근거는 약하며, 대용량 원본 결합·품질·이용 범위를 확인해야 한다.

## 5. 명시적인 graph 정답이 있는 다른 도메인

### Microsoft TaskBench

[공식 자료](https://github.com/microsoft/JARVIS/tree/main/taskbench)는 도구 graph를 먼저 샘플링하고 지시문·호출 파라미터를 만드는 합성 데이터이며, 규칙·LLM·사람 검수를 설명한다. 도구 선택과 의존성 graph 예측을 평가할 수 있다.

이번에 [공식 DailyLife API 데이터](https://huggingface.co/datasets/microsoft/Taskbench/tree/main/data_dailylifeapis)를 읽었다. revision `d12764a8fa650f49f50507a02c3e707c71c67621`에서 4,318개 JSONL row를 확인했으며 `task_nodes`, `task_links`가 있다. 타입 표시는 single 1,257, chain 2,786, dag 275건이다. 라이브러리 전체 graph는 40개 도구와 1,560개 link를 가지며, 이 전체 graph와 각 요청의 정답 호출 graph를 혼동하지 않는다.

**적용:** 자연어 요청→단계·의존성 분해 알고리즘의 정답 평가. **비적용:** Android 화면 인식·좌표·실제 UI 완료 판정.

### CaptainCook4D / Differentiable Task Graph Learning

[공식 DTGL 저장소의 ground_truth_task_graphs](https://github.com/fpv-iplab/Differentiable-Task-Graph-Learning/tree/main/data/captaincook4d/ground_truth_task_graphs)에 요리 절차 graph JSON이 있다. commit `859662b1398bf688dea36245187e90dd114b7326`의 **24개 파일 전체**를 읽었으며, `steps`, `edges`로 총 401개 노드와 477개 간선을 정의한다. 모두 참조 노드가 존재하고 비순환이었다. 노드 수에는 START/END가 포함된다.

**적용:** 시연에서 선행관계를 복원하는 graph 학습, 단계 누락·순서 오류 탐지의 알고리즘 평가. **비적용:** Android UI 전이 및 우리 앱의 정답 경로. 구조 검사를 통과했다는 것만으로 모든 의미 주석의 정확성을 재인증한 것은 아니다.

## 비슷한 이름의 추가 후보: 2026 MobiFlow / MobiBench

[2026 논문](https://arxiv.org/html/2604.09587v1)은 20개 앱·240개 작업의 trajectory-fused state graph를 설명하고 [nanookfyf/MobiBench](https://github.com/nanookfyf/MobiBench)를 연결한다. 이것을 위 MobiAgent 하위 폴더의 DAG rule 개수와 합산하지 않았다.

고정 commit `f90f7998091bf08ea7639523bc2ba0d9a0cd2b25`의 FSM 코드는 annotation tag로 상태를 묶고 시연의 행동 연결을 공유한다. `rawdata.tar.gz`의 공개 LFS 포인터에는 704,860,742 bytes의 archive가 명시되어 있다. 이번에는 작은 포인터·README·코드만 읽었고 본문 다운로드와 240개 작업의 대응·완전성을 확인하지 않았다. 따라서 자료 존재 후보로 남기되 “240개 정답 graph 다운로드 검증 완료”라고 소개하지 않는다.

## Click에 대한 최종 선택

1. **runtime 경로 선택·복구를 평가하려면:** ColorBench의 검수한 부분집합을 첫 연동 후보로 삼는다. benchmark graph는 평가 환경에만 두고 정답 경로·milestone을 실행 agent 입력으로 누출하지 않는다. 운영 graph 자체를 입력으로 사용하는 planner 평가인지, 숨겨진 graph를 복원하는 학습 평가인지 실험을 분리한다.
2. **단계의 의존관계·분기·완료 검증을 설계하려면:** MobiFlow rule 구조를 참고하되, 잘못된 참조·조건 필드를 정리한 뒤 사용한다. 운영 판정은 Click의 실제 사용자 행동·대상·신선한 관측 증거를 유지한다.
3. **AI2의 graph 생성 알고리즘을 연구하려면:** 화면 전이에는 DroidTask/ColorBench, 추상적 graph 복원에는 CaptainCook4D, 요청→도구 계획에는 TaskBench를 각각 사용한다. 이들은 서로 다른 입력과 간선 의미를 평가한다.
4. **우리 실제 서비스 정답을 만들려면:** 위 후보들의 graph 생성·검증 방법을 재사용하되, 지원 Galaxy/One UI/한국어 및 쿠팡·정부24의 경로는 해당 환경의 증거를 연결한다. 이번 조사로 그 세 대상의 제품 정답 graph를 확보한 것은 아니다.

공개 graph 자료를 활용해 알고리즘·검증 체계를 개발할 수 있다는 판단은 강화되었다. 해당 자료를 우리 앱의 정답으로 그대로 치환할 수 있다는 판단은 하지 않는다.

## 재현

Python 3.13 + PyYAML 6.0.3으로 실행했다. 원본 파일은 저장소 밖 cache에 있으며, URL과 hash는 결과 JSON의 `source_manifest`에 있다. 외부 source 코드는 읽기만 했고 import/실행하지 않았다. syntax repair는 ColorBench task의 분석용 메모리 복사본에만 적용했다.

```sh
/private/tmp/task-graph-revalidation/venv/bin/python \
  docs/reviews/evidence/audit_alternative_task_graphs.py \
  --cache /private/tmp/task-graph-alternatives \
  --output /private/tmp/task-graph-alternatives/reproduced.json
```
