# 화면 동일성·성공 기준과 작업 상태 정리

## 적용

- 페이지, 현재 탐색 화면, 실행 대상, 동의 관측, 결과 값을 분리했다. Android 전체 관측 지문은 SHA-256으로 변경했다.
- 승인된 110개 목표에 성공 종류/setting/direction/requested_state를 생성한다. 서버·앱은 동일 계약과 31개 공통 성공/거절 사례를 사용한다.
- CHANGED에는 조작 식별자와 서로 다른 전후 관측 및 시간 순서가 필요하다. 실제 적용값·native checked·adapter power와 목표가 맞아야 한다.
- Bluetooth 전원 켜기와 기기 연결, 페이지 열기와 설정값 변경을 구분한다. 직접 검증하지 못한 사용자의 완료 보고는 USER_REPORTED_RESOLVED로 기록한다.
- TaskExecutionController가 관측/동의/안내/실행/검증/수동 진행/결과/추가 도움/복구/중단/완료 전환을 소유한다. 이전 세대·중복 응답을 공통 전송 경계에서 거른다.
- 수동 UI가 표시되기 전에 관측을 끊지 않는다. UI 예외 시 재시도가 가능한 복구 상태로 전환한다. 명시적 재개 전의 늦은 응답은 중단 화면을 바꾸지 못한다.

상세 기준: [작업 관측과 상태 전환](../task-observation-and-lifecycle.md).

## 검증

- Android 전체: 621개 통과, 실패/건너뜀 없음.
- AI-Agent 전체: 396개 통과.
- Backend 전체: 2104개 통과. 마지막 목표 컨텍스트 우선순위 정리 후 관련 API/계약 120개를 추가 재검증했다. 이 재검증에는 신규 3개 사례가 포함된다.
- 공통 생성 계약 --check 및 git diff --check 통과.
- 실제 gateway callback을 통한 Bluetooth 안내 표시 실패, 중복 callback, 중단 후 늦은 응답, 이전 checkpoint 재선택을 검사했다.
- 실제 개발 기기에 업데이트 설치했고, 설치 APK를 다시 읽어 빌드 SHA-256과 일치함을 확인했다.

APK: Frontend/app/build/outputs/apk/debug/app-task-contract-debug.apk

설치 SHA-256: fa31b12cb1325982d2dedb589e316f4d42d8627c1d748b8882402736cd976a50

## 남은 배포 범위

운영 서버에는 배포하지 않았다. 앱과 동일한 공통 계약 JSON을 포함해 Backend/AI-Agent를 함께 재배포해야 서버 결과 판정까지 반영된다. 연결된 폰에서 실제 설정을 바꾸거나 운영 모델을 호출하는 종단 검증은 수행하지 않았다. 현재 직접 검증할 수 없는 기기 연결·배경화면 적용 자체의 자동 성공 검증을 새로 구현한 것은 아니다.

[기계 판독 증거](evidence/2026-11-10-task-observation-lifecycle.json)
