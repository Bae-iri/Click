# 2026-10-10 UI 하이라이트 재검증

대상: `agent` 작업 트리(HEAD `71a0151`, 미커밋 변경 포함). **판정: 일부 회귀는 통과하지만 잘못된 UI 하이라이트는 미해결이다.** 수정 완료·실기기 통과로 표시하지 않는다.

후속 확인: `develop@860d785`의 API 계약·앱 빌드는 별도 검증에서 FE 71개, Backend 관련 46개 테스트가 통과했다. 아래 초기 작업 트리의 충돌 결과와 혼동하지 않는다. API 계약 수정은 H1~H3 해결 증거가 아니며, 이번 추가 검토에서도 현재 `agent`의 H1 네 반례는 그대로 재현됐다.

## 1. 검증 결과

| 검증 | 결과 | 해석 |
| --- | --- | --- |
| AI-Agent 전체 pytest | 100 passed, 1 failed | 실패는 기존 Backend 충돌 파일의 SyntaxError로 인한 import 경계 검사. 통합 정상 판정 불가 |
| 대상 geometry·행동 연결 Java/JUnit | 10 passed | `GuideTargetGeometryTest` 6개, `GuidedActionTrackerTest` 4개. JVM 검증이며 Android 렌더링 검증이 아님 |
| 추가 의미 오선택 반례 | **4/4 오선택 재현**, 재현 명령 exit 1 | 기존 테스트의 사각지대. 제품 오선택률 100%라는 의미는 아님 |
| Android 기기/에뮬레이터 | `adb devices -l` 목록 비어 있음 | 실기기·WindowManager 표시 미검증 |
| FE 응답 시점·drag 문구 | 소스 흐름 검토 | 아래 H2/H3. UI 자동 재현을 수행했다는 의미는 아님 |

기존 회귀는 클릭 부모의 실제 bounds 보존, 다른 slider/앱/window/숨김·비활성 target 제외, 동점 보류, 관측·행동 연결, false-DONE 방지를 포함한다. 이 통과 결과를 전체 target 의미 정확도로 확대하지 않는다.

## 2. H1 · P0 · ‘화면’ 부분 문자열로 다른 메뉴 선택 — 실행 재현

글자 확대 목표에서 설정 홈에 `설정`, 아래 메뉴 하나, `접근성`이 존재하고 `디스플레이`는 없는 합성 관측을 입력했다.

| 실제 선택된 잘못된 항목 | runtime 안내 | match 점수 |
| --- | --- | --- |
| 잠금화면 | 디스플레이를 눌러주세요. | 0.8 |
| 화면 자동 꺼짐 시간 | 디스플레이를 눌러주세요. | 0.8 |
| 홈 화면 | 디스플레이를 눌러주세요. | 0.8 |
| 화면 보호기 | 디스플레이를 눌러주세요. | 0.8 |

원인: `S1_TO_S2_DISPLAY_MENU.target_keywords`에 `화면`이 있고 `keyword_matches()`는 부분 문자열 포함을 사용한다. 해당 후보가 0.8로 기준을 넘으므로, 명시된 `접근성` 대체 경로를 검토하기 전에 잘못된 target이 선택된다. 사각형은 잘못 고른 node의 실제 bounds이므로 좌표 변환만 수정해도 해결되지 않는다.

수정 인수 기준: 네 항목을 Display와 동일시하지 않고 target을 보류하거나 실제 유효 대체 경로를 선택해야 한다. 정확한 `화면` 이름이 사용되는 환경과 `디스플레이` 정상 메뉴는 보존한다. 전역 부분 매칭을 무조건 없애기보다 graph의 이름·문맥·resource 근거를 구분하고 한국어 복합 메뉴의 반례를 검증한다. graph selector 변경은 승인 artifact의 새 version/hash로 관리하며 기존 실행의 version을 조용히 바꾸지 않는다.

[재현 코드](./evidence/recheck_ui_highlighting.py)와 [실행 결과·소스 hash](./evidence/2026-10-10-ui-highlight-recheck.json):

```bash
PYTHONDONTWRITEBYTECODE=1 python3 docs/reviews/evidence/recheck_ui_highlighting.py \
  --output /tmp/click-ui-highlight-recheck.json
```

exit 1은 재현 성공이면서 제품 동작 실패다. 이 fixture는 개발자가 작성한 합성 반례이며 실제 제품 gold가 아니다.

## 3. H2 · P1 · 최신 화면 검사 이전에 응답 표시 — 소스 확인

`ClickOverlayService.sendAgentTurn().onSuccess()`는 `applyTaskFlowResponse(response)`를 먼저 호출한다. 해당 함수는 `renderGuide()`로 target·음성 안내를 표시한 뒤에야 callback의 뒤쪽에서 최신 fingerprint를 비교하고 다음 관측을 전송한다. `executionGeneration` 검사는 취소/실행 교체를 구분하지만 같은 실행 안의 화면 이동까지 막지 않는다. `renderGuide()`의 fingerprint 비교는 step timer 조건이며 하이라이트 표시 자체를 차단하지 않는다.

우려: 네트워크 대기 중 스크롤·화면 전환이 일어나면 이전 좌표의 안내가 먼저 표시될 수 있다. 표시 지속 시간·실제 기기 발생 빈도는 미측정이다.

수정 인수 기준: target 표시·행동 tracker 연결·TTS 전에 요청 관측과 현재 화면/target의 유효성을 검사한다. 불일치 시 기존 target을 지우고 최신 관측을 요청한다. session version 등 서버 제어 정보 처리는 유지해 재요청 충돌을 만들지 않는다. 같은 화면을 새로 캡처했다는 이유만으로 매번 정상 안내를 버리지 않도록 observation ID와 화면/target 동등성을 구분한다. 늦은 응답 순서·스크롤·회전·앱 전환을 포함한 FE 통합 테스트가 필요하다.

## 4. H3 · P1 · 축소 목표에도 오른쪽 drag 문구 — 소스 확인

`MVP_02_TASK_GRAPH`는 왼쪽 이동을 안내한다. 반면 `GuideOverlayView.onDraw()`는 `actionType == drag`일 때 무조건 `오른쪽으로 움직이세요 →`를 그린다. `showTarget(bounds, action)`은 목표 방향을 전달받지 않는다. 이는 target 선택 오류와 별개인 행동 방향 표시 오류다.

수정 인수 기준: graph의 명시적 방향 또는 일치하는 guide 표현을 FE까지 전달하고 확대/축소 양쪽을 검증한다. locale/RTL 또는 다른 방향의 slider를 지원할 때 방향 계약도 함께 검증한다. 화면 글꼴 변경 성공은 별도 verifier 근거가 있어야 한다.

## 5. 검증 명령과 남은 범위

AI-Agent:

```bash
cd AI-Agent
PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -m pytest --assert=plain -q -p no:cacheprovider
```

Java 검증은 JDK 17, JUnit 4.13.2, Hamcrest 1.3, org.json 20240303으로 geometry/tracker와 두 테스트를 임시 디렉터리에 직접 컴파일해 실행했다. Android 전체 빌드나 계측 테스트를 통과한 것으로 계산하지 않는다.

남은 순서: Backend 기존 충돌 정리 → H1 selector 수정·반례 회귀 → H2 최신 관측과 표시 결합 → H3 방향 표시 → 실제 지원 기기의 설정 화면에서 최종 확인. 실기기에서는 정답 control, 요청/응답/표시 시점, overlay 원점·크기와 표시 bounds를 동시 기록한다. 잘못된 control을 정확히 둘러싼 결과는 실패로 집계한다. [품질·gold 기준](../ai-quality-evaluation.md)

## 6. 해결 대안과 권고

### H1: 후보 자격 검사와 점수 계산을 분리 — 우선 적용 권고

| 대안 | 장점 | 우려·한계 | 판단 |
| --- | --- | --- | --- |
| 알려진 네 메뉴를 제외 목록에 추가 | 변경 범위가 작고 즉시 재현 사례를 막을 수 있음 | 새로운 복합 메뉴, 제조사 표현이 생길 때 계속 보완 필요 | 단기 보완 가능, 단독 해결로는 부족 |
| `화면` 같은 포괄 별칭을 해당 edge에서 정확한 이름으로만 허용하고, package/window/role/부모 행·문맥을 검사 | 정상 `화면` 메뉴를 보존하면서 `잠금화면`을 다른 대상으로 처리 | 정당한 복합 이름은 검수된 제조사/버전별 alias 추가 필요 | **권고안** |
| resource ID만 신뢰 | 의미가 고유한 ID가 있으면 강한 근거 | `android:id/title` 같은 공용 ID는 다른 메뉴도 공유. ID 변경·누락 가능 | 단독 정답 근거로 쓰지 않음 |
| threshold를 일괄 상향 | 적용이 단순 | 0.8의 잘못된 메뉴뿐 아니라 정상 문맥 기반 control도 탈락. 점수는 확률이 아님 | 단독 해결로 비권고 |
| LLM/VLM 또는 OCR로 재선택 | AX가 실제로 부족한 경우 후보 보완 가능 | 새 지연·오류·개인정보 경로 발생. 같은 AX를 OCR로 읽어도 의미 혼동은 남음 | 명시적 동의·마스킹 등 현재 정책을 지킨 보조 경로 |

구체적인 순서는 `현재 상태/앱/window 확인 → 유효한 클릭 행 찾기 → graph별 필수·제외 조건 → 후보 순위 → 동점/불충분 보류`다. 강한 제외 조건은 **similarity 이전 후보 단계**에 둔다. `selector_evidence=0`만 반환하면 `matcher.py`의 similarity fallback이 다시 후보를 살릴 수 있다. resource ID도 공용 식별자인지 확인하고 label/문맥과 함께 쓴다.

전체 `keyword_matches()`를 전역 exact match로 바꾸면 상태 판별·다른 edge까지 바뀐다. graph selector에 `label_match_mode`와 검수된 alias/문맥을 명시하는 작은 계약 확장이 적절하다. DB artifact와 runtime loader가 함께 이해하도록 새 graph version/hash를 게시하고 진행 중 episode는 기존 고정 버전을 유지한다.

### H2: 화면이 유효할 때만 안내 표시

1. callback 수신 시 session/episode/generation·서버 version 등 제어 정보를 먼저 반영한다.
2. target 렌더링·행동 tracker 연결·TTS **직전**에 요청 관측과 최신 관측의 package/window/화면 구조·target identity·좌표를 검사한다.
3. 같은 target·같은 화면임이 확인되고 위치만 바뀌었다면 최신 실제 bounds로 다시 결합한다. 화면 전환·회전 등 의미가 달라졌거나 확인할 수 없으면 이전 안내를 지우고 최신 observe를 한 번 예약한다.
4. 이후 화면 변경 이벤트에서도 표시 중 guide를 무효화한다. 취소·pause·새 episode는 이전 tracker/confirmation/TTS 대기 상태까지 정리한다.

timestamp가 다르다는 이유만으로 정상 응답을 모두 버리거나, 화면이 달라졌다는 이유로 서버 version 갱신까지 버리면 재요청 루프/409가 생길 수 있다. 동일 화면의 새 캡처와 다른 화면을 구분하고 관측 재요청을 합치는 것이 필요하다. 이 순서는 제안이며 실기기 구현·검증 완료가 아니다.

### H3: 의미 방향과 시각 방향을 명시적으로 전달

Backend/AI1 guide에 목표의 `increase/decrease`와 검증된 control의 시각 방향·축 정보를 담고 FE의 글·화살표·음성을 같은 guide ID에 연결한다. 글자 확대라고 모든 slider에서 오른쪽이라고 추측하지 않는다. 방향 정보가 없거나 충돌하면 화살표를 그리지 않고 재관측/설명을 요청한다. MVP에서는 지원하는 제조사·언어의 확대/축소 두 방향부터 확인한다.

## 7. 대안의 작은 실행 실험과 한계

[실험 코드](./evidence/probe_highlight_candidate_gate.py)는 실제 matcher에 **실험 프로세스 내부에서만** Display edge의 정확 별칭 후보 검사를 적용했다. 제품 코드와 graph artifact는 바꾸지 않았다.

- 기존 네 합성 반례: 잘못된 target **4/4 → 0/4**, 모두 graph에 명시된 `접근성` 대체 경로 선택.
- 정상 이름 `디스플레이`, `화면`, `Display`: **3/3** 해당 메뉴 유지.
- 결과는 [실험 JSON](./evidence/2026-10-10-highlight-candidate-gate.json)에 원본 코드 hash와 함께 기록한다.

이는 원인에 맞는 후보 검사가 유효하다는 제한적 증거다. 제품 정확도 100%, 한국어 실기기 해결, 외부 embedding 경로·모든 제조사 검증으로 보고하지 않는다. 새 alias·동명 메뉴·공용 resource ID·미지원 환경 및 H2/H3 기기 검증이 남는다.

최종 인수에는 공개 ScreenSpot의 point accuracy를 그대로 하이라이트 정확도로 쓰지 않는다. 정답 control 식별, 표시 시점, 사각형 IoU/침범, 방향, 실제 설정 변화가 각각 필요하다. [공개 평가 기준 재검증](../ai-quality-evaluation.md#9-공개-기준으로-재구성한-평가-계획)
