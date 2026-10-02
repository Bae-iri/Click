# 절전 모드·화면 잠금 방식: 백엔드 연동 준비

기존 Goal 목록의 세 요청을 백엔드 typed 계약에 연결한다.

`POST /api/v1/agent/commands`의 `goal`에 전달하며,
`target_app=android_settings`, `catalog_version=2.0.0` 또는 `2.1.0`을 사용한다.

| Goal Type | slots |
| --- | --- |
| `settings.power_saving.enable` | `{"setting":"power_saving","state":"on"}` |
| `settings.power_saving.disable` | `{"setting":"power_saving","state":"off"}` |
| `settings.screen_lock.change` | `{"setting":"screen_lock","action":"change"}` |

setting·state·action 불일치, 필수 값 누락, 추가 slots와 1.x catalog 요청은 거부한다.
화면 잠금 요청은 변경 의도만 전달한다. PIN·비밀번호·패턴, 잠금 해제·제거 또는
특정 잠금 방식 선택을 추가 slots로 전달하는 계약은 포함하지 않는다.

기존 승인 그래프 등록·선택·해시 검증·Agent 전달 경로를 재사용한다.
현재 실제 실행 그래프는 없어 내부 `NO_COMPATIBLE_GRAPH`, 외부 HTTP 422
`INVALID_REQUEST`로 응답하며 Agent를 호출하지 않는다.
공유 catalog와 Frontend의 `catalog_only` 상태는 유지한다.

후속 실행 연동에는 AI-Agent(1)의 typed Goal 지원, 기기별 실행 그래프와 완료 조건,
Frontend의 새 화면 관측 전달이 필요하다. 화면 잠금 변경 중 기존 인증과 새 자격 증명
입력은 사용자가 직접 처리하며 민감 입력을 수집하거나 자동 입력하지 않는다.
실제 관측·재현 자료로 검증한 뒤 승인 그래프와 catalog 실행 상태를 함께 반영한다.

테스트는 요청 계약, 구 catalog 차단, 그래프 부재 시 실행 차단,
합성 그래프 중복 없는 등록·선택·Agent 경계 전달과 공유 catalog 일치를 확인한다.
합성 그래프는 서버 계약 검증 전용이며 실제 기기 실행 그래프가 아니다.
