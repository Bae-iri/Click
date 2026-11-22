# 기기 도착 판정 보완: 실기기 재측정 전

사용자가 기기는 나중에 연결하고 코드를 먼저 진행하라고 지시했다. 따라서 아래는 코드 수정·회귀 검사 기록이며, 새 실기기 성공률이 아니다. 기존 9/60, 앱 5/20, 그래프 설정 29/52를 수정 후 수치로 바꾸지 않는다.

## 확인한 원인과 수정

서버의 `app_task_outcome.covers()`는 `page`·`selected` 조건에 대해 네이티브 `button`의 `selected=true`를 완료 증거로 허용한다. 기기의 `AppTaskOutcomes.visible()`은 같은 조건에서 `tab`만 허용했다. 이 때문에 서버가 선택된 하단 이동 버튼을 근거로 도착을 판정해도 기기는 동일한 증거를 거절할 수 있었다.

기기의 판정을 서버와 맞췄다.

- `page`·`selected` 조건 모두 선택된 네이티브 버튼을 확인한다. 실제 `selected=true`가 필요하다.
- 명시적인 탭 선택 요청에서 대상이 네이티브 버튼으로 표현돼도 선택 상태 변화(`selection_changed`, `selected=true`)를 기대하는 동작을 제안한다. 이미 선택된 버튼은 다시 누르지 않는다.
- 출처가 접근성이 아닌 항목, 중복된 대상, 선택되지 않은 메뉴 버튼은 완료 증거로 통과하지 않는다.
- 앱의 사용자 확인 요청은 서로 다른 신선한 네이티브 화면 두 개에서 같은 도착 상태가 유지된 뒤에만 표시한다. 서버의 `satisfied`나 단순 창 변화만으로 성공 처리하지 않는다.

`AppTaskNativeButtonOutcomeTest`는 수정 전 4개 검사 중 3개 실패로 문제를 재현했다. 여기서 실패한 것은 버튼 도착 판정, 명시적 선택 동작 생성, 컨트롤러의 완료 확인 흐름이었다. 수정 후 검증에는 이미 선택된 상태, 탭 후 도착, 탭 후 다른 창 도착, 잘못된 증거의 거부가 포함된다.

## 패턴 A에 대한 확인 범위

`AppTaskController.verifyNow()`는 예상한 동작 효과가 아직 확인되지 않은 경우에도 `observeResult()`로 최종 도착을 확인하는 기존 경로가 있다. `sameNativeWindow`는 모든 동작 검증에 일괄 적용되는 조건이 아니다. 특히 선택 상태 반전 감지나 응답 재사용 등 특정 경로에서 사용한다. 기존 `AppTaskTransitionDetectorTest`에도 다른 네이티브 창으로의 이동과 같은 앱의 공개 대화상자 전환을 허용하는 검사가 있다.

따라서 “Chrome은 창이 바뀌어서 무조건 실패한다”는 가설만으로 창 경계를 해제하지 않았다. 시계·Chrome의 5초 대기는 실제 기대 상태, 고정된 완료 조건, 클릭 이후 화면이 함께 있어야 정확히 분류할 수 있다. 선택된 버튼 불일치 보완이 일부 사례에 도움을 줄 수 있지만, 이전 14회가 모두 해결됐다거나 19/20이 된다고 주장하지 않는다.

다음 실기기 실행을 위해 `LiveAppTaskAuditTest`에 아래 기록을 추가했다. 인증 정보와 입력값은 기록하지 않는다.

- 제안 동작의 `expectation`.
- 기기가 가진 `outcome_goal`, `outcome_bindings`, `completion`.
- 기기의 `result_assessment`.
- 선택된 네이티브 컨트롤의 역할·리소스·출처. 라벨·입력값을 추가로 수집하지 않는다.

## 다음 실기기 검사

기기가 연결되면 별도 진단 앱 `com.clueclick.app.deviceaudit`에 수정 APK를 설치한다. API 주소는 운영 서버의 HTTPS 주소다. 서버 코드는 바꾸지 않았으므로 이번 수정만을 위해 서버를 재배포할 필요는 없다. 시작·종료 버전과 설치 APK 해시를 새로 기록한다.

1. 앱 10과제 × 2회 = **20회**. `LiveAppTaskAuditTest#realProviderNavigationMatrix`, `auditSet=apps`, `auditRepeats=2`, `deviceAuditApps=true`, `deviceAuditSetup=true`.
2. 정상 그래프 경로 설정 열기 20종 + 값 변경 6종 × 2회 = **52회**. `LiveOverlayAuditTest#fullProductMatrix`, `auditRepeats=2`, `auditBudgetMs=60000`, `deviceAuditOverlay=true`, `deviceAuditSetup=true`.
3. 완료는 독립 네이티브 목표 판정과 제품 완료 확인이 모두 필요하다. 첫 유효 안내는 실제 오버레이가 그려진 시각으로 측정한다. 시간 초과·도움 요청도 성공률 분모에 포함한다. 화면 도착률·완료 성공률·거짓 완료·첫 동작 정확도·속도 표본 수를 구분한다.
4. 감사 계정을 정리하고 설정값·접근성 서비스 등을 복원한다. 다른 instrumentation이나 캡처와 동시에 실행하지 않는다.

지도 없는 설정 40회는 다시 돌리거나 수정하지 않는다. `bde84b17`은 기존 52회 측정본 `1e769fe7`의 선행 커밋이라 이미 포함돼 있었다. `a9bf4da5` 등 후속 수정과 현재 배포본의 효과는 새 52회로 확인해야 한다.

## 검사 상태

서버의 선택된 버튼·완료 계약 관련 회귀 검사 **88개 통과**. Android 전체 검사 **1,567개 중 1,562개 통과, 5개 건너뜀, 실패·오류 0개**. 진단 앱과 instrumentation APK 빌드도 통과했다. 기존 노드 경로 안전 검사 추가 이후, 실제 자식 노드를 제공하면서 `getChildCount()`를 설정하지 않았던 삼성 슬라이더·런처 검사 자료도 보완했다. 제품의 경로 범위 검사를 약화시키지는 않았다.

빌드 결과와 소스·APK 해시는 [빌드 기록](arrival-verification-build.json)에 있다. 다음 두 파일을 로컬에 보관했다.

- `output/device-audit/arrival-verification/app-debug.apk`
- `output/device-audit/arrival-verification/app-debug-androidTest.apk`

로그: `button-outcome-final-android.log`, `button-outcome-server-contract.log`. 기기 설치 전 두 APK의 해시가 빌드 기록과 일치하는지 확인한다.

실기기 재측정은 아직 수행하지 않았다.

11월 23일 같은 Android 수정으로 스톱워치와 YouTube Shorts를 실기기에서 재검증했다. [사용자 요청부터 서버 완료까지의 후속 결과](../../../experiments/task-success/2026-11-22/README.md)
