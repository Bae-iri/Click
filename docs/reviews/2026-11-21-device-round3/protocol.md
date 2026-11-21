# 실기기 3차: triage 수정 검증 — 실행 전 프로토콜

- **소스:** develop `f591fc0` 위의 수정이다. 서버 triage에 세 가지 규칙을 넣었다.
  - 목표 대상 문구 일치(포함 관계가 아님)
  - 진입 항목이 보이면 도착 전으로 판단
  - 제목 위치 조건
- **기기 쪽:** 같은 진입 항목 규칙을 넣었다.
- **하네스:** 2단계 과제의 이동 라벨 2개를 허용했고, 판정기 진단을 추가했다.
- **구성:** C(`SpeedOptions.PRODUCTION`) + Gemini + triage 켬. 단일 군이다.
- **과제와 회차:** `settings_app_notifications`, `settings_font`, `settings_sound`, `settings_display`, `settings_notifications`를 r1~r3으로 돌린다. 총 15회.
- **주 지표:**
  - 거짓 완료: 2차에서 앱 알림 12/12였다.
  - 2단계 과제 도달과 verified
  - triage로 확정한 비율
  - 소리 판정기 진단
- 결과를 본 뒤 조건을 바꾸지 않는다.
