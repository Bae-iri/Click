# 프런트엔드 코드 안내

`app`은 실행 앱이며 `design-preview`는 온보딩·계정·권한 화면을 제공하는 UI 라이브러리다. Java 패키지 경로와 Android manifest의 진입점은 유지한다.

## 수정 위치

아래 경로는 `app/src/main/java/com/click/` 기준이다.

| 수정 대상 | 파일·디렉터리 |
| --- | --- |
| 홈 화면 | `HomeActivity.java`, `ui/home/` |
| 플로팅 입력창 | `ui/overlay/ClickOverlayLayout.java` |
| 요청 확인·진행·결과 화면 | `ui/overlay/TaskFlowOverlayView.java` |
| 플로팅 창 색상·간격·배경 | `ui/overlay/FloatingPanelStyle.java` |
| 요청·세션·안내 실행 조율 | `service/overlay/ClickOverlayService.java` |
| 탐색 횟수·방향 전환 | `service/overlay/AutomaticScrollSearch.java` |
| 재관측·메뉴 탐색 조건 | `service/overlay/ObservationRecovery.java` |
| 홈·설정 목록의 실제 스크롤 | `service/accessibility/NativeScrollExecutor.java` |
| 접근성 이벤트·화면 수집 진입점 | `service/accessibility/ClickAccessibilityService.java` |
| 현재 앱 창 선택·노드 수집 | `service/accessibility/ForegroundWindowReader.java`, `AccessibilitySnapshotCollector.java` |
| 버튼·슬라이더 실행 조건 | `service/accessibility/TouchAutomationPolicy.java` |
| 설정별 결과 확인 | `service/overlay/SettingsAdjustment.java`, `AdjustmentResultCheck.java` |
| 목표·사용자 답변 해석 | `domain/goal/` |
| 서버 요청·응답 계약 | `data/remote/` |

스크롤의 단계별 횟수와 중단 여부는 overlay 계층에서 관리한다. 접근성 서비스의 공개 스크롤 메서드는 연결된 서비스를 확인한 뒤 `NativeScrollExecutor`에 위임한다. 실행기는 현재 창 검증, 네이티브 스크롤·제스처, 실행 후 화면 수집을 담당한다. 화면 이동과 설정 변경 결과는 별도로 판단한다.

## 검증

JDK 17과 Android SDK를 사용하고 `Frontend/`에서 실행한다. macOS에서 상위 경로에 `:`가 있으면 Java classpath 처리에 문제가 생길 수 있으므로 `:`가 없는 체크아웃 경로에서 빌드한다.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

- 단위 테스트: `app/src/test/`. 탐색·중단·재관측 연결은 `ClickOverlayServiceExecutionTest`, 네이티브 스크롤 실행은 `LauncherScrollTest`가 검증한다.
- UI·기기 테스트: `app/src/androidTest/`. `AdjustmentFlowUiTest`는 글씨 배율별 결과 화면을 검증한다.
- 실기기 스크롤: `LauncherPageScrollTest`, `SettingsScrollTest`에 `liveScroll=true`를 명시한다. 기존 접근성 연결이 필요하며, 다른 아이콘·항목이 나타나는지 확인한다.
- APK·미리보기·검증 보고서는 각 모듈의 `build/` 아래에 둔다. 원본 이미지와 실행 리소스는 생성물과 구분해 유지한다.

참조가 없는 이전 UI 클래스와 FE 세션 enum은 제거했다. 실제 튜토리얼은 `TutorialActivity`, `ui/tutorial/`, `design-preview`의 권한·튜토리얼 화면을 사용한다.
