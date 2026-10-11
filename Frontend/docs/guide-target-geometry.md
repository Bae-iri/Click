# Guide 좌표와 클릭 영역

2026-10-10 재검증: geometry·행동 연결 JVM 10개는 통과했지만, 부분 문자열 target 오선택 4건과 FE의 오래된 응답 표시 경로·축소 방향 문구 문제가 확인됐다. 좌표 수정만으로 전체 하이라이트가 해결됐다고 표시하지 않는다. [상세 보고서](../../docs/reviews/2026-10-10-ui-highlight-revalidation.md), [품질·정답 기준](../../docs/ai-quality-evaluation.md). 아래 2026-09-12 결과는 당시의 역사적 기록이다.

## 좌표 계약

`AccessibilityNodeInfo.getBoundsInScreen()`으로 수집한 `bounds_in_screen`과
응답의 `guide_command.target_bounds`는 같은 화면의 픽셀 좌표 `[left, top, right, bottom]`다.
Backend와 AI-Agent(1)는 이 좌표를 dp나 Overlay 내부 좌표로 바꾸지 않는다.

`GuideOverlayView`가 그리기 직전에 `getLocationOnScreen()`으로 자신의 화면 원점을 구하고,
`GuideTargetGeometry.toLocal()`에서 다음 변환을 적용한다.

```text
local.left   = screen.left   - overlay.originX
local.top    = screen.top    - overlay.originY
local.right  = screen.right  - overlay.originX
local.bottom = screen.bottom - overlay.originY
```

예를 들어 `[24, 320, 1056, 408]`과 Overlay 원점 `(0, 72)`는
`[24, 248, 1056, 336]`이 된다. 가로·세로 크기는 `1032 × 88px` 그대로다.
상태 표시줄 높이를 상수로 빼거나 density를 곱하지 않는다.
이 처리는 Android의 [화면 bounds](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getBoundsInScreen(android.graphics.Rect))와
[View 화면 위치](https://developer.android.com/reference/android/view/View#getLocationOnScreen(int[])) 계약을 따른다.

- 기존 사방 `8dp` 확장을 제거했다. 테두리도 대상 사각형 안쪽에 그린다.
- 실제 View 영역과 겹치는 부분만 그린다. 크기가 0이거나 뒤집힌 bounds, 완전히 영역 밖인 대상은 그리지 않는다.
- 원점은 매번 다시 측정한다. 변환 결과를 서버로 돌려보내거나 사용자 행동 이벤트의 `target_bounds`를 바꾸지 않는다.
- 둥근 강조 사각형은 조작 영역을 안내하는 표시다. 앱 버튼의 실제 윤곽을 추정하는 기능은 아니다.

## 글자와 클릭 영역 연결

Collector는 노드에 `parent_id`와 `visible_to_user`를 포함한다.
`parent_id`는 같은 snapshot 안의 ID이고, 루트는 `null`이다.
노드 ID 문자열을 잘라 부모를 추정하지 않는다.
Backend schema와 runtime adapter가 두 필드를 AI-Agent(1)에 그대로 전달한다.
기존 평면 payload는 `parent_id=null`, `visible_to_user=true` 기본값으로 호환된다.

다음처럼 클릭 영역에 이름이 없고 글자만 자식 노드로 제공되는 UI를 처리한다.

```text
root
└─ display-row: clickable=true, bounds=[24,300,1056,420], text=null
   └─ display-label: clickable=false, bounds=[64,332,300,388], text="Display"

guide.target_node_id = "display-row"
guide.target_bounds  = [24,300,1056,420]
```

Normalizer는 조작 불가능한 자식의 label을 가장 가까운 적절한 조작 노드의 이름으로 사용한다.
자식의 작은 사각형을 임의로 키우지 않고, 부모의 실제 ID와 bounds를 선택한다.
자식도 직접 조작 가능한 노드라면 그 자식의 ID와 bounds를 유지한다.

이름을 전달할 수 있는 조건은 다음과 같다.

- 자식과 조상은 표시·활성 상태이고, 같은 package에 속하며 양의 면적을 가진다.
- 경로의 각 조상 bounds 안에 글자 bounds가 들어간다.
- 다른 클릭 컨트롤, 스크롤 목록, 입력란 경계를 넘어가지 않는다.
- 부모 자신의 text/content description이 있으면 이를 우선한다.
- 루트와 화면 면적의 50% 이상을 차지하는 부모에는 자식 label을 전달하지 않는다. 이 비율은 화면 전체를 한 항목으로 오인하지 않기 위한 보수적인 정책이다.
- 부모가 없거나 순환하는 비조작 경로는 탐색을 종료한다.

Backend는 후속 turn에도 현재 화면 크기를 runtime에 전달하므로,
첫 요청에만 있는 `raw_goal` 없이도 화면 면적 제한이 유지된다.
숨겨진 노드는 매칭에서 제외하고, 비활성·면적 없는 노드는 조작 대상으로 선택하지 않는다.
Frontend의 Vision fallback은 tree 재포장을 제거하고 매 분석 동의 후 기기 내 OCR을 수행한다. 실제 OCR bounds만 전달하며 화면 밖·면적 없는 결과는 제외한다. [마스킹·실패 대응 및 동의 계약](../../docs/ai-agent-vision-fallback.md)을 따른다.

Snapshot fingerprint에는 부모 관계, 표시 여부, 클릭·활성·스크롤·체크 가능 여부도 반영한다.
AI-Agent(2)의 데이터 분석·graph proposal 동작은 이 변경의 대상이 아니다.

## 검증

2026-09-12 로컬 결과:

- AI-Agent: `python -m pytest -q` — 54개 통과.
- Backend: `python -m pytest -q` — 94개 통과. 부모 ID/bounds 전달, 후속 화면의 위치 변경, 숨겨진 후보 제외를 HTTP 테스트로 검증했다.
- Java/JUnit: `GuideTargetGeometryTest` 6개, 기존 `VoiceRequestQueueTest` 3개 통과. JDK 17과 기존 Gradle 배포본의 JUnit으로 직접 컴파일·실행했다.
- Android 전체 `testDebugUnitTest`/`assembleDebug`는 SDK 위치가 없어 실행하지 못했다. 원본 경로의 `:` 때문에 Gradle wrapper classpath가 깨져, 이 문자가 없는 임시 복사본에서 재시도한 결과다.

SDK가 준비된 환경에서는 다음 명령으로 검증한다. macOS/Linux에서는 프로젝트 경로에 `:`가 없어야 한다.

```bash
cd Frontend
bash gradlew :app:testDebugUnitTest :app:assembleDebug
```

실기기 확인은 아직 남아 있다. 동일한 설정 항목을 기준으로 아래를 확인한다.

1. 상태 표시줄이 있는 화면과 edge-to-edge 화면에서 강조 영역의 상하 위치·크기가 일치하는지 확인한다.
2. 글자가 작은 자식 노드인 설정 행에서 전체 클릭 행이 강조되고 옆 항목이 포함되지 않는지 확인한다.
3. 스크롤 후 새 응답의 bounds가 새 위치를 가리키는지 확인한다.
4. 회전·화면 분할·디스플레이 크기/글꼴 변경 후에도 새 snapshot과 Overlay 원점이 맞는지 확인한다.

좌표 단위 테스트가 실제 WindowManager 배치나 제조사별 Accessibility bounds의 정확성까지 보장하지는 않는다.
화면 확대(magnification), 여러 디스플레이, custom canvas UI도 별도 기기 검증 대상이다.
어긋남이 남으면 같은 시점의 `bounds_in_screen`, 선택된 `target_node_id/target_bounds`,
Overlay `getLocationOnScreen()` 값과 View 크기를 비교해 수집·선택·표시 중 어느 단계에서 달라졌는지 확인한다.
