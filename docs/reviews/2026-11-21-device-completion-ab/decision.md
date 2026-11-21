# 결정: 완료 판정은 C(현재 운영 기본값)를 쓴다

2026-11-21 실기기 A/B([보고서](report.md)) 결과에 따라, 앱 과제의 완료 판정은 **C 구성**을 쓴다.

**C 구성**
- **기기:** `AppTaskController.SpeedOptions.PRODUCTION`. 9인자 이하 생성자의 기본값이므로 운영 앱 코드는 바꿀 필요가 없다.
- **서버:** develop 그대로. 메뉴로 보이는 도착 제목은 `deferred_completion`으로 보내고, 완료 triage는 켠다(`APP_TASK_COMPLETION_TRIAGE` 기본값 1).

**근거** (SM-N981N, 디스플레이·알림, 군당 6회)
- 도착 즉시 기기 자체 완료 확인 6/6. 기준선은 0/6, triage만 켠 군은 5/6이었다.
- 도착 후 추가 모델 호출 0회, 회기당 모델 호출 1회.
- 실패 포함 평균 5.94초. 기준선은 7.98초였다.
- 거짓 완료 0.

**지켜야 할 것**
- 실험 하네스에서 비교군을 만들 때만 `SpeedOptions.NONE` 또는 `ALL`을 명시한다.
  - 실기기: `-e speedAbExperiment true`
  - 공개 앱 하네스: `-e speedOptions`
- `SpeedOptions.PRODUCTION`에서 기억 우선과 다단계 계획은 꺼져 있다. 실기기 비교 전까지 켜지 않는다.
- "소리 및 진동" 판정기 랜드마크("소리 모드")는 이 One UI 화면에 없다. 이 과제를 쓰기 전에 랜드마크를 고친다.
