# 블루투스 Goal: 백엔드 준비와 담당별 연동 사항

공유 Goal 목록에서 와이파이 다음인 블루투스 켜기·끄기·연결의 백엔드 계약을 준비한다.
Frontend와 AI-Agent(1)은 수정하지 않으며, 공유 catalog의 `catalog_only` 상태를 유지한다.

## 백엔드 계약

`POST /api/v1/agent/commands`의 `goal`에 아래 값을 넣는다.
`catalog_version`은 `2.0.0` 또는 `2.1.0`, `target_app`은 `android_settings`다.

| Goal Type | slots |
| --- | --- |
| `settings.bluetooth.enable` | `{"setting":"bluetooth","state":"on"}` |
| `settings.bluetooth.disable` | `{"setting":"bluetooth","state":"off"}` |
| `settings.bluetooth.connect` | `{"setting":"bluetooth","action":"connect"}` |

목표와 일치하지 않는 slots, 누락·추가 필드, 기존 `1.x` catalog 요청은 거부한다.
연결 대상은 사용자가 기기에서 직접 선택하는 범위다. 기기 주소 지정이나 PIN 전달은 현재 계약에 없다.

와이파이 작업에서 마련한 공통 그래프 등록·조회·전달 경로를 재사용한다.
등록된 실행 그래프가 없으면 내부적으로 `NO_COMPATIBLE_GRAPH`를 발생시키고 Agent를 호출하지 않는다.
외부 응답은 기존 오류 정책에 따라 HTTP 422 `INVALID_REQUEST`다.
현재 블루투스 실행 그래프는 없으므로 실제 연결 안내를 시작할 수 없다.

## 담당별 후속 연동

- AI-Agent(2): 실제 관측·재현 자료를 근거로 그래프 후보를 생성하고 정적 검증·오프라인 평가·검토 근거를 준비한다. 이번 변경에 후보 생성 구현은 포함하지 않는다.
- AI-Agent(1): 실행 그래프와 typed Goal 지원, 스위치 상태 판정, 기기 선택·페어링 안내, 실패·완료 조건을 구현한다. 페어링됨과 실제 연결됨을 구분해야 한다.
- Frontend: 스위치 상태 및 연결 상태를 수집하고, 사용자 직접 기기 선택·페어링 확인 뒤 새 화면을 전달한다. PIN 등 입력값 보호가 필요하다.
- 공동 검증 후 graph 등록과 catalog 실행 가능 상태를 반영한다. 후보만 있는 상태에서 실행 가능으로 표시하지 않는다.

## 검증 범위

백엔드 테스트는 Goal/slots·catalog 버전 검증, 실행 그래프 부재 시 Agent 호출 방지,
합성 그래프의 중복 없는 등록·선택·Agent 경계까지 전달을 확인한다.
합성 그래프는 백엔드 계약 검증 전용이며 실제 블루투스 실행 또는 실기기 검증을 의미하지 않는다.
