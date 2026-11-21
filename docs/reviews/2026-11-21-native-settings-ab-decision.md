# 실제 Settings A/B 결정: A 유지, B 폐기

**B는 폐기하고 A를 유지한다.** [2026-11-20 실제 Settings A/B 비교](2026-11-20-native-settings-v2/README.md)에서 B는 Gemini API 시도가 A보다 25% 많았다(48 대 60회). 그런데 공동 성공은 A 4/18, B 3/18로 늘지 않았다. 비용 대비 이득이 없다고 판단했다.

- 운영 기본값은 이미 A(`AppTaskMetaController.Arm.CURRENT`)이므로 동작 변경은 없다.
- `Arm.META`와 백엔드 `recovery` 요청 경로는 위 기록을 재현하는 데 필요하므로 실험 전용 코드로 남긴다. 운영에서는 켜지 않는다.
- `allowed_actions`·실행 capability 전달은 A에도 적용된 공통 수정이므로 유지한다.
- 이후 [MCv2 무반응 비용 회귀](2026-11-21-meta-controller-cost-regression/README.md)에서도 B는 A보다 API 시도가 많았고(7 대 19회) 공동 성공은 양쪽 0/6이었다. 결정은 그대로다.
- 원래 기록과 해시 고정 자료는 수정하지 않았다.
