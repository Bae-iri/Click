# 설정 메뉴 후보 진단

디버그 앱 또는 `-PCLICK_CANDIDATE_DIAGNOSTICS=true`로 만든 내부 테스트 배포본에서
수집 → 전송 필터 → 서버 선택 결과를 확인한다. 이 옵션은 앱을 디버그 가능하게 만들지 않는다.
앱 변경과 서버 변경을 함께 적용해야 전체 단계를 볼 수 있다.

내부 테스트 참여자는 문제를 재현한 뒤 앱의 **메뉴 → 테스트 진단 자료 공유**에서
파일을 전달할 수 있다. 앱이 자동으로 외부에 전송하지 않으며, 사용자가 공유 대상을 선택한다.
배포본은 Logcat 대신 기기의 백업 제외 저장소에 최대 256 KiB 파일 두 개를 순환 저장한다.
공유 파일에는 앱 버전·기종·Android SDK 버전과 관측 시각이 포함된다.
일반 배포는 옵션 기본값이 `false`여서 진단 기록과 공유 메뉴가 비활성화된다.

디버그 앱은 추가로 `ClickCandidates` 태그를 Logcat에 출력한다.

```powershell
& C:/click_server/.android-sdk/platform-tools/adb.exe logcat -v threadtime ClickScroll:I ClickCandidates:I ClickAgent:I '*:S'
```

스크롤 후 같은 `request_id`, `observation_id`를 가진 레코드를 연결한다.
`ClickScroll`의 실행 로그 다음에 새로운 관측 ID가 나타났는지도 확인한다.

| 단계 | 확인할 필드 |
| --- | --- |
| `scroll` | 스크롤 전 관측 ID, 방향, `dispatched`. 디스패치 성공만으로 실제 이동을 단정하지 않음 |
| `preflight` | 전송 전 사전 필터에서 재관측으로 빠지는 경우에도 남기는 원본 구조. 요청 ID 생성 이전이므로 `observation_id`로 연결 |
| `preflight_result` | 전송 전 사전 검사에서 빠졌을 때의 고정된 이유 코드 |
| `collected` | `collected_node_count`, `display_label_count`: 필터 전 원본 접근성 스냅샷에 디스플레이 표현이 있었는지 |
| 노드별 `collected` | `node_hash`, `parent_hash`, `parents`, `missing_parent`, `bounds`, `visible`, `enabled`, `clickable`, `window_id`: 부모 연결 및 클릭 영역 구조 |
| `filtered` | `exported_node_count`, 노드별 `label_exported`, `local_target_exported`: 실제 전송할 필터 결과에 해당 노드가 남았는지 |
| `selection` | `status`, `called`, `candidate_count`, `candidate_diagnostics`: 서버 후보 생성 결과와 AI 호출 여부 |

`candidate_diagnostics`는 서버의 `metadata.debug.ui_selection`과 기존 진단 로그에도 남는다.

- `normalized_nodes`, `display_normalized_nodes`, `display_actionable_nodes`: 정규화 후 전체 노드와 디스플레이 표현·실행 가능한 노드 수.
- `minimized_nodes`, `display_minimized_nodes`: 서버 개인정보 필터를 통과한 노드 수.
- `eligible_pairs`: 역할·패키지·실행 가능 여부 등 기본 후보 조건을 통과한 전이와 노드 쌍 수.
- `selector_rejected_pairs`: 메뉴 이름·리소스·문맥 근거 부족으로 제외된 쌍 수.
- `invalid_slider_pairs`, `offscreen_pairs`, `source_confidence_rejected_pairs`, `excluded_pairs`, `ambiguous_pairs`: 값 오류·화면 밖·인식 신뢰도·이전 제외 대상·중복 식별로 제외된 쌍 수.

예를 들어 원본에 디스플레이 표현이 있고, 서버에도 도착했지만
`display_actionable_nodes=0`, `candidate_count=0`, `called=false`이면 클릭 가능한 항목으로
연결되지 않았는지 부모 구조를 확인한다. 이 값만으로 특정 기종의 원인을 확정하지 않는다.

`local_target`은 앱의 터치 정책으로 해석한 대상이다. 비클릭 텍스트로 대체될 수도 있으므로
`local_target_found`만 보지 말고 대상의 `clickable`과 서버 결과를 함께 확인한다.
정규화 과정에서 텍스트가 부모 행에도 연결되면 디스플레이 노드 수는 원본 글자 수보다 클 수 있다.
쌍 수는 노드 수가 아니며 하나의 노드가 여러 전이의 후보가 될 수 있다.

디스플레이 표현은 `디스플레이`, `화면`, `display`, `screen`의 정확한 표현만 추적한다.
등록되지 않은 다른 표현은 원문으로 로그에 덤프하지 않는다. 원본 노드 ID와 부모 ID는 해시로 남기고,
임의 화면 텍스트·설명·계정 정보는 기록하지 않는다. 관측당 최대 8개 대상과 대상별 6단계 부모만 기록한다.
`collected`만 있고 `filtered`가 없으면 필터 실패 등 전송 이전 경로의 오류 로그도 확인한다.
기기에서 실제로 재현한 로그와 재현용 테스트 데이터 결과는 구분한다.
