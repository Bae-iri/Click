# Task Graph 정답 데이터 적합성 재재검증

확인일: 2026-09-20. [이전 대안 조사](2026-09-20-alternative-task-graph-datasets.md)의 추천 범위를 좁히는 후속 감사다.

**판정: 실재하는 연구·저자 배포 자료라는 근거는 확인했다. 그러나 조사한 자료 중 Click의 graph 선택과 실제 작업 완료 검증을 그대로 평가할 수 있는 완성된 제품용 gold set은 확인하지 못했다.** ColorBench는 graph 내부 경로 평가, MobiFlow는 작업 의존관계·관측 판정 규칙, TaskBench는 도구 호출 graph 예측에 각각 제한해서 활용할 수 있다. 이 차이를 생략하고 “우리 task graph 정답을 확보했다”고 표현하면 안 된다.

이 결론은 모든 공개 데이터에 그런 정답이 없다는 주장이 아니다. 이번에 확인한 배포본·코드·표본에서 확보했다고 입증할 수 있는 범위를 말한다. 구조 검사나 합성 사례 재현만으로 데이터 전체의 의미적 정확도·우리 제품의 성공률은 계산할 수 없다.

## 1. 우리 코드에서 ‘선택’과 ‘검증’이 의미하는 것

현재 작업 트리의 [Backend 선택 코드](../../Backend/app/repositories/task_graphs.py)는 `goal_type`, `target_app`, `active`, `approved`로 후보를 조회하고 `priority`로 선택한다. 최상위 우선순위가 같으면 `GRAPH_SELECTION_AMBIGUOUS`, 후보가 없으면 `NO_COMPATIBLE_GRAPH`다. 선택한 graph의 해시도 확인한다. [AI1 registry](../../AI-Agent/src/ai_agent/runtime/tasks/__init__.py)는 MVP-01 한 graph를 task ID와 goal type 두 키에 등록한다. 따라서 등록 키 두 개를 서로 다른 graph 두 개로 세면 안 된다.

Backend 선택과 AI1의 graph 내부 경로 선택은 별도 문제다. [아키텍처 문서](../ai-agent-architecture.md)도 DB graph 선택·복원을 Backend, 전달 graph 실행을 AI1에 둔다. AI1에 남아 있는 raw-goal 경로의 `_task_graph_for` 역시 task ID/goal type에 따른 dictionary lookup이다. 외부 데이터를 확보한 것만으로 학습된 graph 선택기가 구현되는 것은 아니다.

| 평가 단계 | 필요한 정답 단위 | 원래 공개 데이터에 있어야 직접 평가 가능한 것 |
| --- | --- | --- |
| Graph 선택 | 목표·환경·후보 graph 집합 → 허용 graph 집합 또는 재질문/미지원 | 서로 경쟁하는 후보, 적용 환경/버전, 허용·금지 선택, 애매하거나 지원하지 않는 요청의 라벨 |
| Graph 내부 다음 단계·복구 선택 | 목표·현재 상태·실행 이력 → 허용 행동/전이 집합 | 선행조건, 분기, 되돌아가기, 여러 유효 경로 및 행동 후 관측 |
| Graph 후보 자체의 검증 | graph 정의 → 구조적 유효성 + 실제 적용 가능성 | 참조·분기·도달성 검사, 실제 상태와 전이 증거, 수정 전후 회귀 사례 |
| 실행·완료 검증 | 행동 전 상태·사용자 행동·행동 후 관측 → 성공/실패/판단 불가 | 요청 대상·방향·값의 변화, 사건/관측의 연결, 실패·무변화·잘못된 대상 사례 |

선택 gold는 정답 graph ID 하나로 고정할 필요가 없다. 같은 목표를 안전하게 달성하는 여러 graph가 있으면 허용 집합을 라벨링해야 한다. 단, graph가 하나뿐인 benchmark 환경에서 목표별 경로를 찾는 문제를 곧바로 여러 승인 graph 중 하나를 선택하는 평가로 바꿔 부를 수는 없다.

## 2. 출처와 ‘정답’의 지위

- **ColorBench:** [저자 논문 §3.1, §4.2](https://arxiv.org/html/2510.14621v1)과 [저자 저장소](https://github.com/MadeAgents/ColorBench)를 대조했다. 논문은 여러 task를 하나의 고정 UI 전이 graph에 통합한다고 명시한다. 시연·모델 보완·graph 병합 후 사람 검수를 했다는 설명은 저자의 보고다. 이번 감사가 그 검수를 재수행한 것은 아니다. 저장소의 WWW'26 Oral 표기도 저자 발표로 구분한다.
- **MobiAgent / MobiFlow:** [논문](https://arxiv.org/abs/2509.00531)과 [공식 하위 프로젝트](https://github.com/IPADS-SAI/MobiAgent/tree/4ee794021bb34a8de4d00c52ca8fa6f0065a45ff/MobiFlow)를 확인했다. 공개된 것은 DAG 규칙과 오프라인 trace 검증 프레임워크다. README에는 규칙을 수동 작성하거나 LLM으로 생성할 수 있다고 설명한다. 모든 공개 YAML의 독립적인 사람 검수·판정 정확도가 인증됐다는 근거는 확보하지 못했다.
- **Microsoft TaskBench:** [NeurIPS 2024 공식 논문](https://proceedings.neurips.cc/paper_files/paper/2024/file/085185ea97db31ae6dcac7497616fd3e-Paper-Datasets_and_Benchmarks_Track.pdf)에서 출처를 확인했다. graph를 샘플링한 뒤 GPT-4로 요청·호출 graph를 생성하는 합성 benchmark다. §3.2.4는 사람 검수를 보고하며, §3.3의 품질 비교는 무작위 50개 샘플을 전문가 3명이 평가한다. 이를 전체 배포 row의 무오류 또는 모든 row의 독립 실행 검증으로 확대해석하지 않는다.
- **CaptainCook4D / DTGL:** [NeurIPS 2024 공식 논문 페이지](https://proceedings.neurips.cc/paper_files/paper/2024/hash/6d19163eaec3b0f0accbe462a0139466-Abstract-Conference.html)가 [DTGL 코드](https://github.com/fpv-iplab/Differentiable-Task-Graph-Learning)를 직접 연결한다. 그 코드의 24개 요리 절차 graph는 해당 실험의 reference graph다. Android UI 정답은 아니다.
- **DroidTask, Mobile3M, Mobile-Bench-v2, 별도 2026 MobiBench:** 이전 감사의 원문·배포 목록·표본 확인 범위를 유지한다. 전체 정답 archive, 이미지 및 실제 실행을 확인하지 않은 자료를 이번에도 검증 완료로 승격하지 않았다. MobiAgent의 MobiFlow와 별도 2026 MobiFlow/MobiBench를 하나의 배포본으로 합산하지 않는다.

저자 저장소·학회 논문으로 출처가 확인되는 것, 특정 benchmark의 reference annotation인 것, Click에 적용 가능한 gold인 것은 각각 별도로 판정했다.

## 3. 이번에 추가로 실행한 검증

[재현 스크립트](evidence/probe_task_graph_gold_semantics.py)와 [결과·원본 URL·SHA-256](evidence/task-graph-gold-semantics.json)을 남겼다. ColorBench `tasks.json`, MobiFlow `verifier.py`를 고정 commit에서 다시 내려받아 이전 다운로드와 byte 단위로 동일함을 확인했다. 사용한 원본 파일 해시도 확인했다.

- ColorBench: `1f26a84230d26f99d84eab97d05032582b39018e`.
- MobiAgent: `4ee794021bb34a8de4d00c52ca8fa6f0065a45ff`.
- 공개 함수·클래스의 AST를 그대로 추출해서 실행했다. 로그 출력은 무동작 logger로 교체했다. upstream 전체 CLI, 모델, OCR/LLM API, 기기·emulator는 실행하지 않았다.
- 총 12개 합성 함수 수준 사례와 공개 rule 59개의 실제 loader/DAG 검사를 수행했다. 12는 동작 재현 사례 수이며 정확도 측정용 통계 표본 수가 아니다.

### ColorBench: 실제 완료 집계의 의미

원본 `tasks.json`은 181행의 누락된 쉼표 때문에 다시 파싱에 실패했다. 원본은 유지하고 분석용 메모리 복사본에 쉼표 하나만 넣었다. 그 복사본의 실제 task 1, milestone 4개를 사용했다.

[공개 `get_successful_tasks_by_rule`](https://github.com/MadeAgents/ColorBench/blob/1f26a84230d26f99d84eab97d05032582b39018e/src/test/parse_result.py#L34)에 다음 trace 파일을 넣었다.

| 입력한 합성 trace | 공개 함수의 완료 판정 |
| --- | --- |
| milestone 화면을 목록 순서대로 포함, action 필드 없음 | 완료 |
| milestone 화면을 역순으로 포함, action 필드 없음 | 완료 |
| 모든 action을 명시적으로 `null`로 둠 | 완료 |
| 마지막 milestone 화면을 제외 | 미완료 |

함수는 screenshot ID의 **집합 포함 여부**를 검사한다. 그래서 이 함수 단독으로 순서·정상 전이·사용자 행동·실제 값 변경을 검증할 수 없다. 다만 benchmark의 graph stepping이 다른 제약을 담당할 수 있으므로, 조작된 합성 trace를 집계 함수가 수락했다는 사실을 전체 benchmark의 실제 오판율로 보고하지 않는다.

이전 전체 graph 구조 감사의 확인 사항도 유효하다. graph는 1,987개 화면 ID, 3,154개 방향 간선 쌍을 포함하며, 문법 보정 후 175개 task 중 13개에 graph 전체 노드 집합에 없는 milestone ID가 하나 이상 있다. 이는 13개 task가 모두 해결 불가능하다는 증명은 아니다. 대체 milestone ID·의도·실제 실행을 추가 검사해야 한다. 화면 ZIP은 내려받아 검증하지 않았다.

**적합성:** 검수한 부분집합을 graph 내부 경로·복구 평가에 사용하는 연구 후보. Click graph 선택 gold 및 실제 설정 변경 완료 oracle로 직접 채택하지 않는다.

### MobiFlow: 구조 제약은 있지만 판정 oracle과 별개

[실제 loader](https://github.com/IPADS-SAI/MobiAgent/blob/4ee794021bb34a8de4d00c52ca8fa6f0065a45ff/MobiFlow/avdag/loader.py)와 [DAG 구현](https://github.com/IPADS-SAI/MobiAgent/blob/4ee794021bb34a8de4d00c52ca8fa6f0065a45ff/MobiFlow/avdag/dag.py)으로 59개 공개 YAML을 다시 읽었다.

- 57개가 로드되었고 56개에서 DAG 객체가 생성됐다. **56개가 모두 의미적으로 검수된 gold라는 뜻은 아니다.**
- `feizhu-type7`, `xiecheng_jd-type7`: YAML 문법 오류로 로드 실패.
- `ele-type3`: `add_to_cart.deps`가 정의되지 않은 `select_item_and_specs`를 참조하여 실제 DAG 생성이 실패했다. 이전 보고서의 ‘후속 노드’ 표현은 **선행 노드**로 정정한다.
- `gaode-type1`: DAG 생성은 되지만 success가 정의되지 않은 `start_navigation`을 참조한다.
- `bilibili-type2`: 한 condition의 `llm`이 `params` 밖에 있어 실제 loader 결과에서 params가 빈 dictionary가 된다.

[실제 verifier](https://github.com/IPADS-SAI/MobiAgent/blob/4ee794021bb34a8de4d00c52ca8fa6f0065a45ff/MobiFlow/avdag/verifier.py)의 DAG 처리와 실제 `ui_flag` checker를 실행했다. 관측 boolean은 합성 입력이며 실제 화면 인식 결과가 아니다.

| 입력 사례 | 판정 |
| --- | --- |
| A 후 B, `B.deps=[A]` | 성공 |
| B 후 A | 실패 |
| A 누락 | 실패 |
| AND 선행조건 두 개 모두 만족 후 C | 성공 |
| AND 선행조건 하나 누락 | 실패 |
| OR 경로 첫 번째/두 번째 대안 | 각각 성공 |
| A → B → A 재등장 | 실패 |

마지막 사례는 비독점 `ui_flag` 모드에서 나중의 A 후보가 후속 노드의 검색 시작 위치를 갱신하여, 앞서 있던 A→B 만족 경로를 놓치는 동작이다. 공개 rule의 OCR/LLM 판정도 모두 같은 문제를 겪는다고 일반화하지 않는다. 정상 A→B 사례에도 행동 ID·시간·값 변화는 넣지 않았으므로, 그 성공이 Click의 행동 귀속 검증을 의미하지 않는다.

**적합성:** 의존관계·AND/OR·완료 조건을 표현하는 스키마와 검증 접근법의 참조. 공개 규칙 및 구현을 무검수 정답 판정기로 직접 채택하지 않는다. 관측 판정 정확도는 별도 평가가 필요하다.

## 4. 단계별 채택 판정

‘보조 가능’은 어댑터·검수·대상 환경 일치가 필요한 연구 용도다. Click 통합이나 성능 검증 완료를 뜻하지 않는다.

| 후보 | Graph 선택 정답 | Graph 내부 경로/구조 정답 | 실행·완료 검증 정답 |
| --- | --- | --- | --- |
| ColorBench | 우리 후보 graph 간 선택 라벨 없음 | 부분집합 검수 후 화면 전이·milestone 평가에 보조 가능 | 정적 graph 내 milestone 기준. 우리 행동·값 변화 oracle로는 부족 |
| MobiAgent / MobiFlow | 우리 후보·호환 환경·미지원 선택 라벨 없음 | 작업 DAG의 의존·분기 규칙을 참조 가능 | 규칙과 OCR/LLM 판정 코드. 독립된 무오류 라벨 보장은 아님 |
| TaskBench | 도구 선택·호출 graph 예측에만 benchmark 정답 있음 | tool graph 예측 평가 가능 | Android UI 실행 결과 없음 |
| CaptainCook4D / DTGL | 우리 graph 선택 정답 아님 | 요리 절차 의존관계 학습 평가 가능 | 해당 절차의 오류 탐지 연구용. Android 완료 근거 아님 |
| DroidTask | 직접 확인한 UTG에 우리 선택 gold 없음 | 시연·UTG 후보. 전체 task archive 미검증 | 관측 시연만으로 독립 완료 oracle 확보 안 됨 |
| Mobile3M + Mobile-Bench-v2 | 확인한 task 샘플에 우리 선택 gold 없음 | 전체 graph archive 결합·검증 전 보류 | 실제 evaluator 및 상태 결과를 결합한 재현 전 보류 |
| 별도 2026 MobiFlow / MobiBench | 확보했다고 판정할 수 없음 | FSM 코드 확인, 데이터 archive 본문 미검증 | 검증 완료로 주장하지 않음 |
| AndroidWorld | 우리 승인 graph 선택 라벨 없음 | 모든 정답 graph/경로를 주는 자료가 아님 | 지원 task의 초기화·상태 기반 평가 코드가 유용. Click 대상 task 및 실기기 통합은 별개 |
| AITW / AndroidControl / PixelHelp | 우리 선택 gold 확보 안 됨 | 시연·행동·관측을 graph 후보 생성에 활용 가능 | 시연 행동 일치와 요청 완료는 별도. 전체 후보 graph나 Click 전용 oracle 확보를 뜻하지 않음 |

AndroidWorld·AITW·AndroidControl 관련 직접 원본 검사와 표본 한계는 [이전 재검증](2026-09-20-task-graph-data-revalidation.md)에 기록했다. AndroidWorld emulator를 이번에 실행하거나 미다운로드 corpus를 전수 확인한 것으로 읽으면 안 된다. [AndroidWorld 공식 문서](https://github.com/google-research/android_world)는 지원 환경에서 task 초기화·평가를 제공하는 benchmark라고 설명한다.

## 5. 프로젝트 방향에 적용할 결정

1. **선택 평가의 기준 데이터는 우리 graph catalog에 맞춰 만든다.** 확정 목표와 슬롯, 기기/OS·앱·언어·시작 상태, 후보 graph ID·버전·해시, 허용 graph 집합, 재질문/미지원 라벨, 근거를 묶는다. 일부 항목은 현재 선택 코드가 쓰지 않지만 여러 기기·graph 버전으로 확장할 때 필요하다. 실제 활성 catalog가 한 graph뿐인 MVP에서는 선택 정확도 숫자보다 지원/미지원·모호성·목표 불일치 사례가 우선이다.
2. **실행 gold는 실제 대상 환경에서 별도로 만든다.** 같은 목표에 대한 여러 유효 경로, 잘못된 대상 조작, 무변화, 반대 방향 변화, 이전 화면 재사용, 관측 누락을 포함한다. 글자 크기 증가라면 해당 control의 행동 전후 값과 관측/행동 연결을 수집하고, 실기기의 설정값 또는 독립적인 검수로 정답을 확정한다. 현재 AI1 verifier의 출력을 그대로 gold로 복사하면 그 verifier를 독립적으로 평가할 수 없다.
3. **Graph 후보 검증에는 정적 검사와 실행 근거를 함께 요구한다.** 참조 무결성·선행조건·목표 도달성은 코드로 검사할 수 있다. 실제 앱에서 가능한 전이인지, 완료 predicate가 요청을 정확히 표현하는지는 실제 관측·실행과 회귀 사례로 확인한다. 순환이 필요한 UI 전이 graph에 DAG 제약을 무조건 적용하지 않는다.
4. **공개 자료는 위 기준 데이터의 보조로 도입한다.** ColorBench는 경로 평가 실험, MobiFlow는 의존관계·완료 규칙 설계, TaskBench/DTGL은 graph 추론 연구에 사용한다. 공개 graph를 우리 schema로 변환하면 그것은 우리가 만든 파생 데이터다. 원 출처·변환 규칙·변환 후 검수·미지원 필드를 보존하고 원본의 공식 gold와 구분한다.
5. **AI2의 데이터·검증 역할은 유지한다.** 외부 데이터가 라벨링·우리 환경의 graph 제안·회귀 검증을 대신하지 않는다. 현재 `GraphLearner`, `TrainingDatasetBuilder`, `GraphValidator`는 각각 빈 제안·빈 학습 샘플·`NEEDS_DATA`를 반환하는 골격이다. 외부 데이터 확보만으로 이 파이프라인이 완성되거나 불필요해지는 것은 아니다. 이 역할을 반드시 별도 LLM 에이전트로 실행할지는 별도 구현 결정이다.

선택 알고리즘 변경은 이 데이터와 평가 기준을 만든 뒤 비교한다. 우선 호환 조건으로 후보를 제한하고 선행조건·목표 도달성을 검사하는 기준선을 둘 수 있다. 이번 문서만으로 특정 알고리즘이 기존 우선순위 방식보다 우월하거나 최선이라고 주장하지 않는다.

## 재현 및 한계

```sh
/private/tmp/task-graph-revalidation/venv/bin/python \
  docs/reviews/evidence/probe_task_graph_gold_semantics.py \
  --cache /private/tmp/task-graph-alternatives \
  --output docs/reviews/evidence/task-graph-gold-semantics.json
```

Python, NumPy, PyYAML 및 이전 감사에서 받은 고정 버전 파일을 사용한다. 캐시가 없으면 결과 JSON의 source manifest URL에서 원본을 받고 SHA-256을 대조해야 한다. 이 감사는 외부 연구 데이터의 전체 품질 인증, 이미지 의미 전수 검수, 실기기 통합 테스트, 모델 성능 측정이 아니다. 제품 코드는 변경하지 않았다.
