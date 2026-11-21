# T(완료 단계화) 단독 검증 — 프로토콜 (실행 전 고정)

[이전 묶음 실험](../2026-11-21-paper-ideas/report.md)에서 T는 6번 작동해 모두 맞는 제목을 묶었고, 결과 확인 호출을 없앴다. 다만 다른 장치 네 개와 한 팔에 묶여 있었다. 이번에는 **T만 켠 팔**을 B와 비교한다.

## 비교군

| 팔 | 구성 |
|---|---|
| B | develop + 운영 기억(정확 일치) |
| C | B + T만 (`serve_memory_experiment.py --triage-only`, 하네스 `-e paperIdeasArm triage`). 다시 붙이기·첫 단계 기억·M1·M3은 끈다 |

T 규칙은 이전과 같다([묶음 프로토콜](../2026-11-21-paper-ideas/protocol.md)의 T 항목).

## 과제와 일정

모델 없는 준비 검사를 통과한 과제 중 세 앱 6과제다([setup-probe.json](setup-probe.json)).

| 과제 | 앱 | 역할 |
|---|---|---|
| settings_display, settings_notifications, settings_sound | 설정 | T가 작동할 수 있는 페이지 제목 과제 |
| chrome_menu, chrome_new_tab | Chrome | 범용성: T가 해를 끼치지 않는지 |
| youtube_subscriptions | YouTube | 범용성: T가 해를 끼치지 않는지 |

settings_sound는 이전 기억 실험에서 정답 조건이 사후 확인되지 않았다. 거짓 완료가 나오면 정답 조건 문제인지 먼저 따로 확인한다.

회기 rep1~rep3, 6과제 × 3회 × 2팔 = **36회**. 예산 25초, 독립 정답 700ms 유지 뒤에만 모의 사용자 확인, `verifyWaitMs=8000`.

## 판정

다음을 모두 만족하면 **T 운영 반영 검토 GO**:

1. 거짓 완료 0.
2. 목표 도달과 기기 자체 인정이 B보다 적지 않다. 어떤 과제에서도 2회 이상 적지 않다.
3. Gemini 호출 합계가 B보다 적다. T가 작동한 회기에서 도착 후 인정까지 시간이 B보다 짧다.
4. Chrome·YouTube 과제에서 B와 차이가 없다(T가 작동하지 않거나, 작동해도 거짓 완료 0).

## 변경 기록

(실행 이후 변경은 여기에 기록한다.)

1. **1차 실행(live-v1-invalid) 무효 — T 구현 버그.** 기기는 `goal_first` 요청의 응답에 `goal_assessment`가 없으면 `INVALID_TASK_GOAL_ASSESSMENT`로 거부한다. T 응답에 이 필드와 `active_subgoal`이 없어서, `goal_first` 요청에 대한 T 응답이 기기에서 `IOException`으로 끝났다(1차 C 2회, 이전 묶음 실험 C 2회). T가 현재 증거를 직접 확인하므로 모델의 `satisfied` 답과 같은 읽기 전용 계약(`goal_assessment=satisfied`, 계획 불변, 첫 대기 하위 목표)으로 응답하게 고쳤다. 서버만 바뀌었고 APK는 같다. 과제·일정·기준은 그대로 2차를 실행한다.
2. **settings_sound 정답 조건 오류 확인.** 모델 없이 기기에서 소리 설정 화면을 덤프한 결과, 제목 "소리 및 진동"은 있고 하네스 정답이 찾는 "소리 모드" 라벨은 없다. 이 과제의 "거짓 완료"는 정답 조건 오류로 보고 주 판정에서 제외하고 따로 보고한다. 과제 구성은 비교 가능성을 위해 바꾸지 않는다.
