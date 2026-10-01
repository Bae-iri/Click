# 비행기 모드·핫스팟: 백엔드 연동 준비

비행기 모드와 모바일 핫스팟의 켜기·끄기 네 Goal을 검증한다.
Frontend, AI-Agent(1), 공유 catalog의 `catalog_only` 상태는 변경하지 않는다.

## 요청 계약

`POST /api/v1/agent/commands`의 `goal`에 전달한다.
`target_app=android_settings`, `catalog_version`은 `2.0.0` 또는 `2.1.0`이다.

| Goal Type | slots |
| --- | --- |
| `settings.airplane_mode.enable` | `{"setting":"airplane_mode","state":"on"}` |
| `settings.airplane_mode.disable` | `{"setting":"airplane_mode","state":"off"}` |
| `settings.hotspot.enable` | `{"setting":"hotspot","state":"on"}` |
| `settings.hotspot.disable` | `{"setting":"hotspot","state":"off"}` |

서로 다른 setting, 방향 불일치, 필수 값 누락, 추가 slots, `1.x` catalog 요청을 거부한다.
핫스팟 SSID·비밀번호 변경, 연결된 기기 관리, USB/Bluetooth 테더링은 이번 계약에 포함하지 않는다.

기존 공통 그래프 등록·선택·전달 경로를 재사용한다. 현재 실행 그래프는 없으므로
내부 `NO_COMPATIBLE_GRAPH` 오류가 발생하고 Agent를 호출하지 않는다.
외부에는 기존 오류 정책에 따라 HTTP 422 `INVALID_REQUEST`로 응답한다.

## 담당별 후속 연동

- AI-Agent(2): 실제 관측과 재현 자료에 기반한 그래프 후보·오프라인 평가 자료를 준비한다. 이번 변경은 후보 생성 pipeline 구현을 포함하지 않는다.
- AI-Agent(1): typed Goal 지원, 실행 그래프, 스위치 상태·완료 검증, 비활성 컨트롤과 실패 처리를 구현한다. 핫스팟 켜짐과 다른 기기의 연결 또는 인터넷 사용 가능 상태를 구분한다.
- Frontend: 실제 스위치 상태 수집, 사용자 확인·직접 조작, 변경 뒤 새 관측을 전달한다.
- 비행기 모드 전환 또는 핫스팟 전환으로 서버 연결이 끊겼을 때의 재연결·세션 복구를 공동 검증한다.
- 후보 검토 및 실행 연동 검증 후 graph와 catalog의 실행 가능 상태를 반영한다.

테스트는 네 Goal의 요청 검증, 그래프 부재 시 실행 차단, 합성 그래프 등록·선택·Agent 경계 전달을 확인한다.
합성 그래프는 백엔드 계약 검증 전용이며 실제 실행 구현 또는 실기기 검증이 아니다.
