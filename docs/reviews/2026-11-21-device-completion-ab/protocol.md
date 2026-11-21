# 실기기 완료 판정 A/B: 실행 전 프로토콜

실행 전에 고정한다. 결과를 본 뒤 조건을 바꾸지 않는다.

## 환경

- **기기:** SM-N981N / Android 13. 격리 실험 앱 `com.click.metamemoryexperiment.uitestdriver`.
- **서버:** 로컬 facade(`serve_memory_experiment.py --speed-ab`)와 develop `f1ceb1b` 기반 서버. 계획기는 Gemini 3.8 Flash, goal-first다.
- **정책:** A/CURRENT, 기억 꺼짐(learning=false, 서버 user_id 없음). 완료 판정 경로만 비교한다.

## 군

| 군 | 기기 SpeedOptions | 서버 completion triage | 의미 |
|---|---|---|---|
| A | NONE | 끔 | 오늘 아침 기준선 |
| B | NONE | 켬 | triage(`c5e0298`)만 |
| C | PRODUCTION | 켬 | 현재 운영 그대로(미래 전용 후보, 예측 보관, 화면 재찾기, 1회 재시도 포함) |

## 배정

- **과제 4개:**
  - 메뉴 라벨과 도착 화면 제목이 같은 설정 과제 3개: `settings_display`, `settings_sound`, `settings_notifications`
  - 대조용 탭 과제 1개: `clock_timer`
- **반복:** 과제마다 r1~r3 3회.
- **순서:** 군 순서는 하네스의 고정 회전·역전 방식이다(무작위 아님).
- **규모:** 총 36회. 회기 예산 25초, 목표 도달 뒤 검증 대기 1.5초.
- **모의 사용자:** 독립 UiAutomation 정답이 700ms 유지된 뒤에만 확인을 누른다.
- 실패한 회기는 분모에서 빼지 않고, 추가 실행으로 채우지 않는다.

## 지표

**주 지표:**
- 네이티브 `verified` 제시 비율. 오늘 실기기 첫 단계 실험에서는 0/42였다.
- 목표 도달부터 `verified`까지 걸린 시간
- 회기당 모델 호출 수
- 도착 확인 전용 호출(`result_only`) 수
- 목표 도달률

**보조 지표:**
- `deferred_completion` 발생 수
- triage로 모델 없이 끝난 응답 수
- 공급자 오류와 재시도

**안전:** 거짓 verified가 하나라도 나오면 중단한다.

## 한계

기기 한 대, 군당 12회다. 효과의 방향과 크기만 보는 탐색 실험이다.

## 실행 전 변경 (결과를 보기 전)

모델 호출이 없는 시작 상태 점검에서 `clock_timer`가 10초 안에 준비되지 않았다(`setup-probe.json`). 그래서 이 과제를 빼고
설정 과제 3개 × 3군 × 3회 = **27회**로 실행한다.
