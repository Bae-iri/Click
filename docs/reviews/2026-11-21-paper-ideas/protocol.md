# 논문 아이디어 + 기억 수정 실기기 비교 — 프로토콜 (실행 전 고정)

## 질문

두 논문에서 가져온 장치와 기억 수정이 현재 develop보다 **Gemini 호출을 줄이거나 완료를 빠르게 하면서, 성공을 줄이지 않고 거짓 완료를 만들지 않는가?** 효과가 있어 보이는 장치를 골라내는 1차 거름 실험이다.

- AutoDroid-V2 (arXiv 2412.18116): 요소를 식별자로 다시 찾는다. 단계마다 모델에 묻지 않는다.
- MemGUI-Bench (arXiv 2602.06075): 완료 판정을 싼 검사 먼저, 애매할 때만 비싼 판정으로 단계화한다. 실패 원인의 다수가 기억 오류다.

기기 위 SLM 학습과 앞단계 계획(L)은 이번 범위에서 뺐다. 실행 시간을 줄이기 위해서다(설계 초안 160회 → 32회).

## 비교군

같은 하네스(`MemoryDeviceExperimentTest -e paperIdeasExperiment true`)와 실험 서버(`serve_memory_experiment.py --paper-ideas`)를 쓴다. MC 정책은 두 팔 모두 CURRENT(A)다.

| 팔 | 구성 |
|---|---|
| **B** | develop 그대로 + 운영 기억(정확 일치 회수) |
| **C (=N)** | B + 아래 다섯 장치 |

| 장치 | 위치 | 내용 |
|---|---|---|
| T 완료 단계화 | 서버 `AppTaskService.step(completion_triage=True)` | 완료 조건이 없고, 검증된 동작이 하나 이상 있으면 모델 호출 전에 확인한다. 현재 화면에서 목표 문장에 그대로 들어 있는 **누를 수 없는 페이지 제목** 또는 **선택된 탭**이 정확히 하나이고 기존 서버 안전 검사(`_observed_goal_check`)를 통과하면, 그것을 완료 조건으로 묶고 모델을 부르지 않는다. 앱 이름과 일반어(설정, 화면, 메뉴 등)는 제외한다. 아니면 기존대로 모델을 부른다 |
| R 다시 붙이기 | 기기 `AppTaskController(rebindExperiment=true)` | 탭 제안 응답이 왔는데 화면이 바뀌었으면, 같은 native 창에서 라벨·리소스·역할이 같은 누를 수 있는 노드가 정확히 하나이고 실행 정책이 허용할 때만 그 노드로 옮겨 제안한다. 아니면 기존대로 버린다 |
| M1 시스템 거절 분리 | 서버 회수 `_ground(system_rejections_neutral=True)` | 실행기가 아예 보내지 않은 동작(`rejected`)을 실패 근거·거절 수·회피 전술(`choose_another_native_target`)에서 뺀다 |
| M3 근거 쪼개짐 수정 | 서버 회수 `_ground(merge_visible_expectation=True)` | 기억 재실행이 `target_visible`을 `screen_changed`로 내보내 자기 근거를 둘로 쪼개던 묶음 키를 합친다 |
| 첫 단계 기억 | 기기 `firstStepMemoryExperiment` (팀원 구현, 평가 회기만) | 완료 조건이 묶이기 전에도 기억 동작을 쓴다 |

모든 장치는 기본값이 꺼져 있다. 운영 생성자와 `step()` 기본 인자는 바뀌지 않는다. 제안은 기존 실행 정책·단계 검증·사용자 승인을 그대로 거친다.

## 과제와 일정

어제 정답 대조가 확인된 과제 중 4개다. 실행기 거절이 기억을 오염시킨 사례(시계)를 포함한다.

| 과제 | 앱 |
|---|---|
| settings_display | 설정 |
| settings_notifications | 설정 |
| clock_timer | 시계 |
| clock_world | 시계 |

회기: learn1, learn2, learn3, eval1. 기억 재사용에는 확인된 작업 2개가 필요하므로 학습을 3회 둔다. 4과제 × 4회기 × 2팔 = **32회**. 과제·회기마다 팔 순서를 결정적으로 회전한다(무작위 아님). 예산 25초, 독립 정답(UiAutomation) 700ms 유지 뒤에만 모의 사용자 확인, `verifyWaitMs=8000`.

실행 전 모델 없는 준비 검사(`probeMemorySetups`)에서 4과제가 모두 준비되어야 한다. 시계는 알람 탭에서 시작한다.

## 지표

**주 지표 (팔별)**

1. 독립 목표 도달과 기기 자체 완료 인정(`runtime_verified_offer`).
2. Gemini 호출 수(`observed_model_calls`)와 공급자 오류 수.
3. 거짓 완료: 정답 없이 완료를 인정한 회차. **0이어야 한다.**

**장치별 작동 기록**

- T: 서버 로그의 `reason_code=completion_triage` 응답 수, 정답 성립 뒤 기기 인정까지 시간(`runtime_verified_after_goal_ms`).
- R: 회차별 `changed_frame_discards`, `changed_frame_rebinds`, `planner_model_rebound` 출처 제안 수.
- M1/M3, 첫 단계 기억: 회수 로그의 거절 수·근거 묶음, 기억 출처 제안(`memory_*`) 수와 그 회차의 도달.

## 판정

- C가 거짓 완료 0, 도달·자기 인정이 B보다 적지 않고, Gemini 호출 합계가 B보다 적으면 **해당 작동 장치를 분리 검증할 후보로 GO**.
- 장치별 효과는 작동 기록으로만 추정한다. 한 팔에 묶었으므로 인과 분리는 아니다.
- 32회, 과제당 팔별 4회는 작은 표본이다. 차이가 과제 하나에서만 나면 "우열 판단 불가"로 기록한다. 기기 1대, 사람 참가자 없음.

## 변경 기록

(실행 이후 변경은 여기에 기록한다.)
