# 모바일 데이터 Goal: 백엔드 연동 준비

블루투스 다음인 모바일 데이터 켜기·끄기의 백엔드 계약을 추가한다.
Frontend, AI-Agent(1), 공유 catalog의 실행 상태는 변경하지 않는다.

## 요청 계약

`POST /api/v1/agent/commands`의 `goal`에 전달한다.
`target_app=android_settings`, `catalog_version`은 `2.0.0` 또는 `2.1.0`이다.

| Goal Type | slots |
| --- | --- |
| `settings.mobile_data.enable` | `{"setting":"mobile_data","state":"on"}` |
| `settings.mobile_data.disable` | `{"setting":"mobile_data","state":"off"}` |

목표와 다른 상태, 다른 setting, 필수 값 누락, 추가 필드와 `1.x` catalog 요청은 거부한다.
SIM 선택·통신사 설정·APN 변경은 이번 계약에 포함하지 않는다.

## 그래프 연동

기존 공통 그래프 등록·선택·Agent 전달 경로를 재사용한다.
현재 실행 그래프는 없으므로 내부 `NO_COMPATIBLE_GRAPH` 오류가 발생하며 Agent를 호출하지 않는다.
기존 HTTP 오류 정책에 따라 외부에는 422 `INVALID_REQUEST`로 응답한다.
요청 계약 지원은 실제 모바일 데이터 조작 안내 완료를 의미하지 않는다.

## 담당별 후속 작업

- AI-Agent(2): 실제 관측·재현 자료로 그래프 후보와 오프라인 검증 자료를 준비한다. 이번 변경에 후보 생성 구현은 포함하지 않는다.
- AI-Agent(1): typed Goal과 실행 그래프, 모바일 데이터 스위치의 상태·완료 검증, SIM 부재·비활성 컨트롤·실패 처리를 구현한다. 켜짐과 실제 인터넷 연결을 구분한다.
- Frontend: 스위치 상태 수집 및 변경 후 새 화면 전달, 사용자 확인·직접 조작 안내를 구현한다.
- 연동 검증 후 실행 그래프 등록과 catalog 실행 상태를 반영한다. 모바일 데이터 끄기로 서버 통신이 끊기는 경우의 복구 흐름도 함께 확인한다.

백엔드 테스트의 합성 그래프는 등록·선택·전달 경계 검증용이며 실기기 실행 구현이 아니다.
