# 와이파이 Goal: 백엔드 준비와 담당별 연동 사항

현재 변경 범위는 Backend다. AI-Agent(1) 런타임과 Frontend 구현은 각 담당자가 진행한다.
공유 catalog의 와이파이 세 항목은 `catalog_only`로 유지한다. 실제 실행 지원 완료를 뜻하지 않는다.

## 백엔드 구현

`POST /api/v1/agent/commands`에서 아래 Goal과 slots를 검증한다.
`target_app=android_settings`, catalog version은 `2.0.0` 또는 `2.1.0`을 사용한다.

| Goal | slots |
| --- | --- |
| `settings.wifi.enable` | `{"setting":"wifi","state":"on"}` |
| `settings.wifi.disable` | `{"setting":"wifi","state":"off"}` |
| `settings.wifi.connect` | `{"setting":"wifi","action":"connect"}` |

- 요청 검증은 실행 그래프 존재 여부와 분리한다. 모순되는 slots와 추가 비밀번호 필드는 거부한다.
- 기존 초기화 절차는 런타임 registry에 등록된 그래프를 task ID로 중복 제거해 DB에 등록한다.
- Agent adapter는 선택된 그래프의 task ID와 task type을 전달한다. 글자 크기 전용 ID로 바꾸지 않는다.
- 승인된 실행 그래프가 없으면 내부적으로 `NO_COMPATIBLE_GRAPH` 오류가 발생하고 Agent를 호출하지 않는다. 기존 HTTP 오류 정책에 따라 외부에는 422 `INVALID_REQUEST`로 응답한다.
- 현재 와이파이 실행 그래프는 없으므로 세 Goal 모두 실행을 시작하지 않는다.

## AI-Agent(2) 담당 범위

관측 데이터·재현 fixture를 근거로 신규 그래프 후보를 생성하고 정적 검증·오프라인 평가·검토 자료를 준비한다.
후보 작성은 실제 런타임 지원이나 승인과 다르다. 이번 변경에서 Agent 2의 후보 생성 pipeline을 새로 구현하지는 않았다.
실행 중 화면 판단·완료 검증을 Agent 2로 옮기지 않는다.

## AI-Agent(1) 담당자와 합의할 사항

- 켜기·끄기·연결용 실행 그래프, task ID, 상태·전이·실패 처리 및 완료 조건.
- typed Goal 검증에 와이파이 세 종류를 추가하고 런타임 registry에 검토된 그래프 등록.
- `checked` 기반 스위치 판정, 이미 목표 상태인 경우, 연결 중·실패·연결 완료의 구분.
- 연결할 네트워크는 사용자가 직접 선택하는 계약이다. 특정 SSID 지정이 필요하면 FE/BE slots 계약부터 함께 확장한다.
- 후보 검증 및 담당자 검토가 끝난 후 실행 가능 상태로 전환. 미검증 후보를 실행 그래프로 등록하지 않는다.

## Frontend 담당자와 합의할 사항

- 스위치의 실제 상태 `isChecked()`를 `extras.checked`로 전달하고 화면 변경 감지에도 포함하는 방안.
- 비밀번호 및 편집 입력값을 화면 전송 전에 제거하는 처리.
- 네트워크 직접 선택·비밀번호 직접 입력 안내와 새로운 화면 관측 전달.
- Agent 1 구현 및 연동 검증 후 공유 catalog의 실행 상태 변경.

## 검증

Backend 테스트는 Goal/slots 검증, 그래프 부재 시 실행 거부, 합성 테스트 그래프의 중복 없는 등록·선택·Agent 경계까지 전달을 확인한다.
합성 그래프는 백엔드 검증 전용이며 실제 와이파이 실행 구현이나 실기기 검증의 근거가 아니다.
