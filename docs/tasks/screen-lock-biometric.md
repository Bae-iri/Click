# 화면 잠금 방식·생체 인식: 백엔드 계약

두 기능은 동일한 보안 설정 작업 묶음으로 검증한다.

| Goal Type | slots |
| --- | --- |
| `settings.screen_lock.change` | `{"setting":"screen_lock","action":"change"}` |
| `settings.biometric.setup` | `{"setting":"biometric","action":"setup"}` |

`POST /api/v1/agent/commands`, `target_app=android_settings`, catalog 2.0.0 또는 2.1.0을 사용한다. 기존 구현을 재사용하며 두 기능의 계약·catalog 일치·승인 그래프 전달을 함께 검증한다. PIN, 비밀번호, 패턴, 생체 정보, 잠금 제거와 잠금 방식 선택을 추가 slots로 전달하면 거부한다.

실제 기기용 승인 그래프는 아직 없다. 따라서 현재 요청은 HTTP 422 `INVALID_REQUEST`로 종료하고 Agent를 호출하지 않는다. 합성 그래프 테스트는 전달 경계 검증만 수행한다. 기기별 화면·인증·완료 관측을 검증한 실행 그래프를 등록하기 전까지 catalog는 `catalog_only`로 유지한다.
