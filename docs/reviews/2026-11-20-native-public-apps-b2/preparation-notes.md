# 실제 앱 준비 과정과 Files 관찰 차이

이 문서는 모델 호출 전 준비 과정만 기록한다. 성능 결과나 B의 개선 효과를 제시하지 않는다. 실행 소스는 [동결 기록](source-freeze.json)의 `63ecb80a3ae22582bf37570df480ec95035b1fd2`이며, [환경](environment.json)·[카탈로그](../../../experiments/metacontroller/native-public-apps-catalogue-v1.json)·[전체 일정](manifest.json)을 모델 요청 전에 고정했다. 준비에서 확인한 앱은 Settings, Clock, Files다.

## 처음 Files drawer가 비어 보인 이유

초기 홈 관찰에서는 Files의 빈 Downloads 화면이 생산 `AppTaskObservation`의 `public` 상태였고 노드가 11개였다. 그러나 `Show roots`를 수동으로 연 뒤 별도 probe Session을 시작한 두 관찰은 `empty`, 노드 0개였다. [초기 평가](preparation/files-support-assessment.json), [실패 관찰](preparation/probe-files-roots-visible.json), [실제 drawer 화면](preparation/files-current.png)은 그대로 보존했다. 이 초기 평가의 미지원 판단은 후속 검증으로 대체됐으며, 최종 카탈로그의 지원 판정이 아니다.

중간 진단에서 Android는 active/focused인 application window 하나와 비활성 상태 표시줄만 보고했다. 다른 앱·키보드·자체 overlay가 앞에 있다는 근거는 없었다. [동시 native window 메타데이터](preparation/files-live-window-metadata.json)와 [독립 UI 트리](preparation/files-ui-automation-public-drawer.json)에서 drawer 라벨은 존재했지만 행과 조상은 `clickable=false`였다. 이 자료만으로 `ForegroundWindowReader` 결함이나 생산 privacy 정책 결함을 확정하지 않았다.

초기 수동 탐색은 probe Session 사이, 접근성 서비스를 원래 OFF 상태로 복원한 구간에서 이뤄졌다. 로컬 Android SDK의 `AbsListView` 구현은 행 생성 때 접근성이 켜져 있어야 항목 접근성 delegate를 붙인다. 따라서 측정과 같은 조건에서 다시 확인했다. **같은 Session에서 접근성을 유지하고 홈을 다시 연 다음, 변경하지 않은 생산 `AppTaskExecutor`로 `Show roots`를 실행했다.**

[동일 Session 관찰](preparation/probe-files-roots-same-session.json)은 `public`이었고, native root와 snapshot window가 일치했다. Recent·Documents·Downloads 등의 실제 행은 `clickable=true`, native `ACTION_CLICK=16`, `CollectionItemInfo`를 가졌으며 생산 projection에도 이름이 있는 button으로 나타났다. 이는 행 생성 시점의 접근성 상태가 달랐다는 설명과 일치한다. 생산 window 선택·privacy·click 권한을 완화하거나 좌표로 조작 권한을 추가하지 않았다.

이어 [Recent](preparation/probe-files-recent-same-session.json)와 [Documents](preparation/probe-files-documents-same-session.json)까지 생산 native 실행기가 실제 탐색을 수락했다. 목적 판정은 drawer 항목이 아닌 고유한 비행동 header `Recent files`와 `Files from Documents`의 `id/header_title`을 사용한다. Files 전체 기능의 지원을 주장하는 결과는 아니다.

Chrome 대체 실행은 자동 승인 검토에서 실행 전에 차단됐다. 당시 검토는 Files 홈이 public인 상태에서 대체 조건이 충분히 입증되지 않았다고 판단했다. 우회하거나 재시도하지 않았으며, 이후 Files를 같은 Session에서 검증해 대체가 필요 없어졌다. Chrome은 실행·평가하지 않았다.

## 최종 여섯 목표와 권한 범위

| 앱 | 시작 상태 | 목표 | 독립 목적 증거 |
| --- | --- | --- | --- |
| Settings | 홈 | Display & touch | 정확한 collapsing toolbar 제목 + Brightness level |
| Settings | 홈 | Language & region | System 경유, 정확한 collapsing toolbar 제목 + Preferred Languages |
| Clock | Clock 탭 | Timer | 정확한 `tab_menu_timer`의 native selected=true |
| Clock | Clock 탭 | Stopwatch | 정확한 `tab_menu_stopwatch`의 native selected=true |
| Files | Downloads | Recent | `header_title`의 Recent files |
| Files | Downloads | Documents | `header_title`의 Files from Documents |

Settings의 실제 홈·스크롤 위치·System·목적 화면과 Clock의 선택 전/후 탭, Files의 drawer·목적 화면을 관찰했다. [카탈로그 유도 기록](preparation/catalogue-derivation.json)에 원본 probe 해시가 있다. 공개 관찰 라벨·리소스에는 실제 읽기 전용 완료 증거를 포함하고, 실행은 그보다 작은 정확한 탐색 selector 집합으로 제한한다. Clock 숫자 키패드·Start, 알람 변경, Files 항목 열기, 설정값 변경은 실행 권한에 포함하지 않는다. 스크롤도 고정한 탐색 목록 selector만 허용한다.

최종 [readiness](readiness.json)는 6개 목표의 시작 음성 대조와 실제 GUI 양성 경로를, [native guard](guard-readiness.json)는 공개 native 실행과 오래된 화면/다른 창/보호 입력 화면의 거절을 기록한다. 이 준비와 [실행 전 검사](verification.json)는 성능 시행이나 사용자 연구로 합산하지 않는다. 이후 성능 시행에서도 접근성을 유지한 Session 안에서 시작 화면을 다시 구성한다.
