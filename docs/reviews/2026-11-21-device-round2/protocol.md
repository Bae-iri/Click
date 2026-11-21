# 실기기 2차: 공급자, 재시도, 기억·다단계 계획 — 실행 전 프로토콜

실행 전에 고정한다. 결과를 본 뒤 조건을 바꾸지 않는다. 기기는 SM-N981N / Android 13, 앱은 `com.click.metamemoryexperiment.uitestdriver`다.
실험마다 새 facade(`serve_memory_experiment.py --arm-config`)를 띄운다. 정책은 A/CURRENT, goal-first다. 기기 속도 경로의 기본값은 C 구성(`PRODUCTION`)이다.

**판정기 수정 (실행 전, 실제 화면 덤프 근거):**
- `settings_sound`: "소리 모드" → "벨소리"
- 새 2단계 과제 `settings_font`: "글자 굵게"
- 새 2단계 과제 `settings_app_notifications`: "전체"

## E1 공급자 (C 구성 고정)

| 군 | 공급자 | 모델 |
|---|---|---|
| A | Gemini | 3.8 Flash |
| B | OpenAI | gpt-6.2-sol, 추론 low |

- 둘 다 triage 켬, 기억 꺼짐이다.
- 과제는 `settings_display`, `settings_sound`, `settings_notifications`, 회기는 r1~r3이다. 총 18회.
- **주 지표:** 목표 도달, 네이티브 verified, 목표 도달까지 시간, 실패 포함 평균 시간, 호출 수, 공급자 오류.

## E3 시간 초과 재시도

- 두 군 모두 Gemini를 쓰고, 첫 계획 요청을 facade가 8.63초 뒤 `provider_timeout`으로 응답하게 주입한다.

| 군 | SpeedOptions | 재시도 |
|---|---|---|
| A | `PRODUCTION_NO_RETRY` | 끔 |
| B | `PRODUCTION` | 켬 |

- 과제는 `settings_display`, `settings_notifications`, 회기는 r1~r3이다. 총 12회.
- **주 지표:** 목표 도달과 verified. 재시도 군은 회복해야 하고, 재시도를 끈 군은 실패가 예상된다.

## E2 기억 우선 + 다단계 계획

| 군 | SpeedOptions | 기억 우선 | 다단계 계획 |
|---|---|---|---|
| B | `PRODUCTION` | 끔 | 끔 |
| C | `ALL` | 켬 | 켬 |

- 두 군 모두 학습을 켜고 군별 저장소를 따로 쓴다. 서버 기억은 정확 일치 회수다.
- 과제는 2단계 과제 `settings_font`, `settings_app_notifications`다.
- 회기는 learn1~learn3과 eval1~eval3이다. 총 24회(학습 12, 평가 12).
- **주 지표:** 평가 회기의 호출 수, 시간, 목표 도달, 기억·계획 경로 제안 수. 학습 비용은 따로 본다.

## 공통

- 회기 예산은 25초(E2는 30초), 목표 도달 뒤 검증 대기는 1.5초다.
- 모의 사용자는 독립 판정기가 700ms 유지된 뒤에만 확인을 누른다.
- 거짓 verified가 나오면 원인을 확인한다. 판정기 결함인지 기기 오판인지 실제 화면으로 확인하고 보고한다.
- 실패는 분모에 남긴다. 결과를 본 뒤 추가 실행이나 조건 변경은 하지 않는다.
- 기기 한 대, 군당 6~12회의 탐색 실험이다.
