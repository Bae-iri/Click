# 생체 인식·앱 삭제·앱 권한 관리: 백엔드 연동 준비

기존 공유 catalog의 세 Goal을 `POST /api/v1/agent/commands` 요청 계약에 연결한다.
`target_app=android_settings`, `catalog_version=2.0.0` 또는 `2.1.0`이다.

| Goal Type | slots |
| --- | --- |
| `settings.biometric.setup` | `{"setting":"biometric","action":"setup"}` |
| `settings.apps.uninstall` | `{"setting":"apps","action":"uninstall"}` |
| `settings.app_permissions.manage` | `{"setting":"app_permissions","action":"manage"}` |

setting·action 불일치, 누락, 추가 slots와 1.x catalog 요청은 거부한다.
현재 계약은 작업 의도만 받는다. 특정 앱 package, 개별 permission, 허용·차단 상태,
PIN·비밀번호와 생체 정보는 slots에 포함하지 않는다.

기존 승인 Task Graph 등록·선택·해시 검증·Agent 전달 경로를 재사용한다.
실제 실행 그래프가 없으면 Agent를 호출하지 않으며 내부 `NO_COMPATIBLE_GRAPH`,
외부 HTTP 422 `INVALID_REQUEST`로 응답한다. 공유 catalog와 Frontend의
`catalog_only` 상태는 유지한다.

## 실제 그래프 전달 이후 연동 조건

- 생체 인식: 기기별 지문·얼굴 등록 화면, 미지원 기기, 기존 인증 단계와 등록 완료 조건을 검증한다. 사용자가 직접 인증·등록하며 자격 증명이나 생체 정보는 수집·자동 입력하지 않는다.
- 앱 삭제: 삭제할 앱의 식별, 삭제 불가 시스템 앱, 삭제 확인과 완료 관측을 검증한다. 데이터 소실을 수반하는 최종 삭제 단계에는 기존 행동 승인 계약을 적용한다.
- 앱 권한 관리: 대상 앱·권한·허용 범위를 명확히 정하고 기기별 권한 선택 화면과 변경 완료 조건을 검증한다. 위험 행동은 기존 승인 계약에 맞춰 처리한다.

AI-Agent(1)의 typed Goal 지원과 실행 그래프, Frontend의 사용자 확인·직접 조작·새 관측
연결을 함께 검증한 뒤 승인 그래프와 catalog 실행 상태를 반영한다.

테스트는 세 요청의 계약, 구 catalog 차단, 추가 입력 거절, 그래프 부재 시 실행 차단,
합성 그래프 중복 없는 등록·선택·Agent 경계 전달, 공유 catalog 일치를 확인한다.
합성 그래프는 백엔드 계약 검증 전용이며 실제 기기 실행 그래프가 아니다.
