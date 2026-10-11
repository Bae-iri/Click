# Task Graph 데이터·검증 도구 재검증

검토일: 2026-09-20. 대상: AndroidWorld, Android in the Wild(AITW), AndroidControl, PixelHelp와 현재 Click MVP-01 및 AI2 코드.

**판정: 공개 자료를 후보 경로 추출·대상 찾기·제한된 작업 평가에 활용하는 것은 가능하다. 그러나 이 자료들이 우리 제품의 정답 Task Graph와 완료 검증을 이미 제공한다는 근거는 없다. 실제 지원 단말·앱에서 수집하고 검수한 자료를 주 데이터로 두는 것이 현재 근거에 맞다.**

앞선 제안 중 AndroidWorld를 “우리 검증의 중심”으로 바로 채택할 수 있다는 인상을 준 부분은 수정한다. 그것은 어댑터와 작업별 evaluator를 구현하고 실험해야 하는 설계 제안이었다. Click 통합이나 삼성 글자 크기 확대의 성공을 확인한 결과가 아니었다. 외부 시연을 후보 그래프로 만드는 AI2의 역할과 실제로 완성된 구현도 구분해야 한다.

## 무엇을 정답이라고 부를 수 있는가

- **시연:** 특정 환경에서 사람이 수행한 관측·행동 순서. 한 경로의 증거가 된다. 다른 분기, 실패 복구, 실행 조건까지 정의하지는 않는다.
- **Task Graph:** 재사용할 상태, 행동 전이, 전제조건, 대상 선택 규칙, 완료 조건, 지원 환경의 정의. 여러 시연을 병합하는 과정에는 추가적인 추론과 검수가 필요하다.
- **환경의 성공 판정:** 예를 들어 시스템 밝기가 255인가를 확인하는 함수. 목표 달성 여부의 한 조건을 평가한다. 경로의 모든 전이나 사용자에게 보여 준 안내의 정확성을 증명하지 않는다.
- **Click의 완료 판정:** 안내와 연결된 사용자 행동, 동일 대상, 신선한 관측 및 전후 값 등을 확인한다. 외부 데이터의 완료 표시나 ADB 값과 같은 계약이 아니다.

하나의 관측 경로에 맞는 그래프는 여러 개일 수 있다. 성공한 경로 하나에서 관찰되지 않은 전이의 유효성이나 모든 실패 분기를 추론할 수는 없다. 또 시연과 다른 경로로 성공할 수 있으므로, 시연 행동 일치율을 실제 완료율로 해석하면 안 된다.

## 이번에 실제로 실행한 확인

고정한 원본 버전과 각 파일 SHA-256, 입력 범위, 결과는 [실행 결과 JSON](evidence/task-graph-data-revalidation.json)에 기록했다. [검증 스크립트](evidence/task_graph_data_revalidation.py)는 원본 데이터를 저장소 밖에서 읽고 메타데이터만 출력한다.

| 대상 | 실제 확인 | 확인 범위의 한계 |
| --- | --- | --- |
| AndroidWorld | commit `e3fea3ccc69787570e282c99573298f1c3019a34`의 작업·평가·관측·registry 코드를 읽음. 실제 `TaskEval` 및 밝기 evaluator 클래스의 AST를 추출해 가짜 ADB 응답 5개로 실행 | ADB 응답 모킹, initialized flag 주입. 초기화·실제 기기·Click 통합은 실행하지 않음 |
| AITW | google-research commit `4700efb9afa54286b0e04473ba80a13e8461e25f`. 공식 GoogleApps shard 0 전체 6,125,004 bytes를 다운로드해 88 TFRecord, 10 episode ID를 파싱. 모든 record CRC32C, RGB buffer 크기, UI annotation 배열 길이 확인 | 편의 표본 한 파일. 전체 corpus의 앱/작업 분포를 추정하지 않음 |
| AITW 행동 평가 | 원본 `_check_drag_actions_match` 함수 AST로 반대 방향 드래그 등 3개 반례 실행 | 이 scalar helper의 `jnp.abs/equal`에 NumPy 사용. 전체 JAX 평가 루프를 실행한 것은 아님 |
| AndroidControl 원본 | 기존 공식 shard 0의 16 MiB prefix를 별도 환경에서 다시 파싱: 완전한 4 episode, 18 관측, 14 행동, 6,070 AX node. CRC·이미지·행동/관측 정렬 확인 | 4건 표본. 끝의 불완전한 record 제외 |
| AndroidControl 범위 | 공개 파싱본의 15,283개 메타데이터 및 기존 package index를 재집계하고 공식 split ID와 일치 확인. 기존 원본 네 prefix 112 MiB의 43건 대조 스크립트도 다시 실행 | 전체 49.93 GB 원본 전수 검사는 아님. AX ZIP 전체 추출은 이전 검토 결과를 이용 |
| Click | 현재 실제 `verify_transition`에 합성 fixture 6개를 입력하여 상태·완료 결과 확인 | 사용자 이벤트 연결 과정과 full engine, FE/BE 통합을 실행한 것은 아님 |
| PixelHelp | 공식 README의 공개 범위·스키마를 재확인 | 이번에 TFRecord를 다운로드하거나 재생하지 않음 |

이번 확인 환경에서는 PATH의 `adb`, `emulator`를 찾지 못했다. **AndroidWorld 실제 재생과 Click 전체 흐름 검증은 미실시**이며, 성공률 수치를 제시하지 않는다.

## AndroidWorld: 완료 평가 코드는 있으나 우리 그래프는 없다

[TaskEval](https://github.com/google-research/android_world/blob/e3fea3ccc69787570e282c99573298f1c3019a34/android_world/task_evals/task_eval.py)은 목표 템플릿·파라미터, 초기화, 성공 판정, 정리를 제공한다. 이것은 Click의 상태 ID·전이·selector·안내·복구 분기를 포함한 정답 그래프가 아니다.

[시스템 작업 코드](https://github.com/google-research/android_world/blob/e3fea3ccc69787570e282c99573298f1c3019a34/android_world/task_evals/single/system.py#L29)의 밝기 판정은 ADB로 `settings get system screen_brightness`를 읽는다. 실제 함수에 응답을 주입한 결과는 다음과 같다.

| 목표 | 가짜 ADB 반환값 | evaluator 결과 |
| --- | ---: | ---: |
| 최대 밝기 | 255 | 1 |
| 최대 밝기 | 254 | 0 |
| 최소 밝기 | 1 | 1 |
| 최소 밝기 | 0 | 0 |
| 최소 밝기 | 255 | 0 |

따라서 “UI 모습만 보고 성공이라고 하는 것이 아니라 환경 값을 검사한다”는 설명은 맞다. 하지만 이 함수는 밝기 전용 최종 조건이며, 글자 크기 확대에 대한 판정도, 사용자 안내 수행의 증거도 아니다. 초기 상태를 목표와 반대로 만드는 일반 task와 이미 목표 상태인 `Verify` task도 구분해야 한다.

검사한 [registry](https://github.com/google-research/android_world/blob/e3fea3ccc69787570e282c99573298f1c3019a34/android_world/registry.py)와 시스템 작업 코드에는 현재 MVP의 시스템 글자 크기 확대 evaluator가 등록되어 있지 않았다. 해당 작업을 검증하려면 별도로 정의해야 한다. 공식 설정 환경은 Pixel 6 / Android 13 에뮬레이터이므로, 이를 통과해도 삼성 One UI에서 같은 경로가 작동한다는 증거는 아니다. [공식 설치 안내](https://github.com/google-research/android_world/blob/e3fea3ccc69787570e282c99573298f1c3019a34/README.md)

Click은 사용자가 안내를 따라 행동하는 제품이다. AndroidWorld의 자동 행동 실행기로 안내의 대상·방향을 대신 실행하면 **기계가 해당 행동을 실행했을 때의 결과**를 평가할 수 있다. 사람이 실제로 안내를 이해했는지, 올바른 대상에 행동했는지의 평가는 별도로 남는다. 연결에는 목표/graph 등록, 관측 변환, 안내→시험 행동 변환, 결과 대조가 필요하다. [새 task 작성 문서](https://github.com/google-research/android_world/blob/e3fea3ccc69787570e282c99573298f1c3019a34/docs/tasks_guide.md)

ADB/DB 상태는 시험용 별도 정답으로 보관해야 한다. 운영 입력에 없던 값을 planner에 주거나 `accessibility_range` 출처로 바꿔 기록하면 다른 조건을 평가하게 된다.

## AITW: 관측 경로 자료이며 완료·방향 정답으로 그대로 쓸 수 없다

실제 원본의 18개 필드는 [공식 스키마](https://github.com/google-research/google-research/blob/4700efb9afa54286b0e04473ba80a13e8461e25f/android_in_the_wild/README.md#dataset-format)와 일치했다. 목표, 화면, OCR/UI annotation, 행동 종류·좌표, episode/step ID가 있었다. 원본 AX tree, Click graph, 안내 ID, 슬라이더의 구조화된 전후 값은 없었다. 이 표본의 `image/encoded`는 PNG/JPEG가 아닌 raw RGB였으며 88개 모두 412×732×3 bytes로 복원했다.

10개 episode ID 모두 마지막 관측 행동이 `STATUS_TASK_COMPLETE`였지만 이는 수집자의 종료 표시다. 시스템 값을 읽어 확인하는 evaluator와 같은 증거로 취급하지 않는다.

추가로 두 ID에서 이 파일 내 record 수와 `episode_length`가 달랐다.

| episode ID | 이 파일의 record 수 | 선언된 길이 | 관찰한 step ID |
| --- | ---: | ---: | --- |
| `2729424677228029046` | 7 | 14 | 0–6, 마지막 action=10 |
| `6160402803482314990` | 6 | 12 | 0–5, 마지막 action=10 |

원인은 확인하지 않았다. 전체 corpus의 결함률로 일반화하지 않으며, 이 두 episode는 추가 조사 전 완전한 경로로 채택하지 않는 것이 맞다.

더 직접적인 제한은 [드래그 평가 함수](https://github.com/google-research/google-research/blob/4700efb9afa54286b0e04473ba80a13e8461e25f/android_in_the_wild/action_matching.py#L142)다. 좌표 차이의 절댓값에서 주축을 골라 비교하므로 다음 결과를 냈다.

| 두 행동 | 함수의 일치 판정 |
| --- | --- |
| 오른쪽 vs 왼쪽 드래그 | true |
| 아래 vs 위 드래그 | true |
| 가로 vs 세로 드래그 | false |

따라서 이 metric을 글자 크기 확대의 성공 지표로 그대로 채택하면 안 된다. 작업별 대상·방향·결과 값을 평가해야 한다. AITW는 여러 기기에서 관찰된 경로·좌표의 후보 자료로는 쓸 수 있지만, 삼성/한국어 MVP 수록량은 이번 작은 표본으로 확인하지 못했다.

## AndroidControl: AX가 유용해도 우리 MVP 정답은 확보되지 않았다

공식 원본에서 AX와 이미지·행동의 대응을 재확인했다. 그러나 관측된 8개 최상위 필드에 Graph의 상태·전이·전제조건·완료 판정 정의는 없다. 사용한 [AX protobuf](https://github.com/google-deepmind/android_env/blob/main/android_env/proto/a11y/android_accessibility_node_info.proto)에도 구조화된 `RangeInfo.current/min/max` 필드가 없다. 일부 수치가 텍스트에 등장할 가능성과 공식 완료 증거가 있는 것은 구분해야 한다.

앱 수록 범위는 [기존 상세 검토](2026-09-20-android-control-mvp-app-coverage.md)와 이번 [메타데이터 재집계](evidence/task-graph-ac-coverage-crosscheck.json)가 일치했다.

- 공식 split과 같은 15,283개 episode ID의 파싱 메타데이터를 확인했다. 기존 AX package index에서 `com.android.settings`는 169건, `com.coupang.mobile`과 `kr.go.minwon.m`은 각각 0건이다. 설정 앱의 등장 횟수는 고유 task 수나 삼성 시연 수가 아니다.
- font/text-size 등 키워드 67건과 Settings 패키지 등장 episode의 교집합은 0건이었다. 모든 표현에 대한 부재 증명은 아니지만, 시스템 글자 크기 확대 정답을 확보했다는 주장을 지지하지 않는다.
- 원본 43건을 다시 대조했을 때 행동·단계 지시문·관측 수·패키지는 파싱본과 일치했다. goal 완전 일치는 0/43, 원문 prefix 일치는 41/43으로 파싱본의 문자열 변형도 재확인했다. 학습/graph 생성에는 검증한 원본을 사용해야 한다.
- 원본 episode 34의 글자 크기 변경은 Google Play Books 내부 작업이다. 시스템 글자 크기 확대의 정답으로 재분류할 수 없다.

전체 앱 범위의 근거는 제3자 파싱본과 기존 AX 집계이며, 공식 원본의 직접 확인은 43건이다. 이 한계를 유지한다. 공식 자료가 설명하는 수집 기기도 Pixel이다. [저자 데이터 설명, p.2](https://papers.nips.cc/paper_files/paper/2024/file/a79f3ef3b445fd4659f44648f7ea8ffd-Supplemental-Datasets_and_Benchmarks_Track.pdf)

## PixelHelp: 작은 지시문·대상 대응 평가 자산

[공식 공개본](https://github.com/google-research-datasets/seq2act/tree/master/data/pixel_help)은 187개 다단계 지시문과 UI 요소 및 `Ui_target_id_seq` 등을 제공하는, 원 논문의 데이터에서 갱신된 버전이다. 지시문 구간과 행동 대상의 연결을 평가할 때 참고할 수 있다. 이 스키마는 모든 분기·복구를 가진 Click graph나 시스템 설정 값 evaluator를 제공하지 않는다. 이번에는 문서 확인만 했으므로 원본 파싱·통합 적합성은 확인 전이다.

## 현재 Click 완료 검증 및 AI2와의 실제 간격

[MVP-01 graph](../../AI-Agent/src/ai_agent/runtime/tasks/mvp_01_font_size_increase.py)의 마지막 조작은 `target_value_increased`를 요구한다. 실제 [검증 함수](../../AI-Agent/src/ai_agent/runtime/verification.py)에 합성 입력을 넣은 결과:

| 입력 조건 | 결과 | 완료 처리 |
| --- | --- | --- |
| 연결된 사용자 행동 없음 | WAITING_FOR_ACTION | false |
| 기준값 없음 | UNKNOWN | false |
| 현재값 없음 | UNKNOWN | false |
| 같은 대상, 연결된 행동, 신선한 관측, 값 증가 | SUCCESS | true |
| 밝기 슬라이더를 글자 크기 대상으로 제출 | UNKNOWN | false |
| 행동 전의 오래된 관측 | WAITING_FOR_OBSERVATION | false |

이는 공개 데이터가 누락한 증거를 가져오는 것만으로 채울 수 없다는 확인이다. 테스트의 event key는 합성 fixture이고 실제 수집자의 행동 기록에서 얻은 값이 아니다. 실제 사용자 이벤트 연결 자체는 이번 probe에서 시험하지 않았다.

또한 현재 AI2의 [GraphLearner](../../AI-Agent/src/ai_agent/graph_proposal/proposals/graph_learner.py)는 빈 proposal을, [TrainingDatasetBuilder](../../AI-Agent/src/ai_agent/graph_proposal/datasets/dataset_builder.py)는 빈 samples를 반환한다. [GraphValidator](../../AI-Agent/src/ai_agent/graph_proposal/evaluation/graph_validator.py)는 `NEEDS_DATA`를 고정 반환하는 뼈대다. `has_regression=False`도 실제 회귀 테스트 결과로 읽으면 안 된다. 공개 데이터에서 그래프를 자동 생성·검증하는 완성된 파이프라인은 현재 없다.

## 근거에 맞춘 채택 방향

| 자료 | 채택할 수 있는 역할 | 아직 채택할 수 없는 주장 |
| --- | --- | --- |
| 지원 단말·앱에서 검수한 자체 시연 | MVP 경로·대상·분기·완료 증거의 주 자료 | 수집하지 않은 버전까지 일반화 |
| AndroidControl | AX 기반 경로 후보 추출·대상 찾기 방법의 보조 자료 | 삼성 설정·쿠팡·정부24 정답 graph 확보 완료 |
| AITW | 화면·행동 시연에서 경로 후보를 추출하는 보조 자료 | 공식 행동 일치 metric = 우리 완료 정확도 |
| AndroidWorld | 지원 task에 대한 별도 환경 평가 PoC 및 evaluator 작성 방식 | 기존 코드를 붙이기만 하면 Click/MVP 검증 완료 |
| PixelHelp | 지시문→대상 연결에 관한 작은 참고 평가 | 제품 전체 task graph gold set |

진행 순서는 다음이 타당하다.

1. 실제 지원 Galaxy/One UI/언어의 글자 크기 확대부터 reference graph를 만든다. 정상 변경 외에 반대 방향, 무변화, 이미 최대값, 다른 슬라이더, 대상/관측 누락, 지원할 대체 진입·취소 사례를 구분한다. 각 검수 전이에 원본 관측과 환경 버전을 연결한다.
2. 학습·graph 수정에 쓰지 않은 자체 시연으로 상태/대상/전이 및 잘못된 완료 선언을 평가한다. 외부 시연에서도 누락·길이·방향·대상 모호성 검사를 먼저 통과시킨다. 생성한 ID와 약한 라벨을 원본 증거로 표시하지 않는다.
3. AndroidWorld는 격리된 작은 연동 실험으로 검증한다. 공개된 밝기 task를 쓰면 Click에 별도 task/목표/관측·행동 어댑터가 필요하다. 글자 크기를 쓰면 해당 초기화와 evaluator부터 필요하다. 실제 환경 판정과 Click 완료 판정이 성공·실패 사례에서 일치하는지 확인한 뒤 확대 여부를 결정한다.
4. AI2는 수집→정규화→후보 제안→회귀 검증의 오프라인 기능으로 유지할 수 있다. LLM이 독립적인 agent로 상시 실행되어야 한다는 근거는 없으며, 현재는 사람이 검수한 작은 graph와 결정적인 검사부터 구현하는 것이 적절하다.

공개 데이터와 evaluator의 기술적 활용 가능성, 제품 개선 효과, 상업적 이용 조건은 별개다. AndroidControl 공식 README·저장소 루트·저자 보충자료의 이용 조건 표기 차이는 [이전 검토](2026-09-20-android-control-data-review.md#이용-조건에서-해결되지-않은-부분)에 남아 있으며, 이번에도 보충자료 p.4의 비상업적 조건 문구를 확인했다. 이를 해결한 것으로 가정하지 않았다.

## 재현 범위

실행 환경: Python 3.13, protobuf 7.36.2, numpy 2.5.3, Pillow 12.3.0, google-crc32c 1.8.0. 실제 사용 버전·proto source hash·외부 source URL 및 hash는 실행 결과 JSON에 있다.

검증 스크립트는 다운로드를 자동 수행하지 않는다. JSON의 `source_manifest.sources`에서 기록된 파일을 cache에 준비하고 공식 TF Example/Android accessibility protobuf에서 생성한 모듈 및 AndroidControl prefix를 준비한 후 실행한다. 이번 세션의 로컬 cache를 사용하는 명령은 다음과 같다.

```sh
/private/tmp/task-graph-revalidation/venv/bin/python \
  docs/reviews/evidence/task_graph_data_revalidation.py \
  --cache /private/tmp/task-graph-revalidation \
  --ac-cache /private/tmp/android-control-review \
  --output /private/tmp/task-graph-revalidation/reproduced.json
```

외부 스크린샷과 TFRecord 원본은 저장소에 넣지 않았다. 런타임의 hash는 이번 probe 시점 코드이며, 작업 중인 다른 변경이 반영된 이후에는 현재 코드와 달라질 수 있다.
