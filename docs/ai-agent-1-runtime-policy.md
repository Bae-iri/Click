# AI-Agent(1) 검증·UI 선택·Re-plan 실행 정책

2026-10-10 코드 재확인 기준. 최초 신뢰성 수정은 2026-09-12이며 아래 당시 시험 기록은 역사적 결과로 보존한다. 현재 DB graph 본문 decode·version 고정, FE 행동 수집·FONT_SCALE 검증이 구현되어 있으나 Backend 충돌과 [UI 오선택 재현](./reviews/2026-10-10-ui-highlight-revalidation.md)이 남아 있다. [품질·gold 기준](./ai-quality-evaluation.md)이 인수 기준이다. 코드 존재와 실기기 통과를 구분한다.

## 1. 실행 순서와 소유권

1. AX 관측을 정규화하고, 필요한 경우 동의된 Vision 보조 관측까지 확정한다.
2. 현재 state와 classifier 점수를 계산한다. 검증으로만 도달할 수 있는 state는 화면 분류에서 제외한다.
3. 직전 pending attempt에 행동 event를 귀속한다.
4. 원래 target·기준값과 새 관측으로 결과를 검증한다.
5. 확인된 실패만 집계하고 제외 target을 유지한다.
6. 전달 graph의 transition priority와 selector로 다음 UI를 선택한다.
7. 대기·재관측·재시도·대체 transition·Backend 반환 중 하나를 결정한다.
8. Safety 확인 후 guide와 pending attempt를 저장한다.

AI1은 DB graph를 수정하거나 새 경로를 생성하지 않는다. 진행할 수 없으면 `backend_handoff.type=task_graph_reselection_required`와 사유를 반환한다. 2026-10-11 수정: 복구는 `highlight / observe_only`와 `render_policy.recovery`로 전달하고 episode를 유지한다. adapter는 복구 및 legacy graph handoff의 정책을 보존하며 결과 확인으로 변환하지 않는다. 실제 안전 차단은 `BLOCKED`, 검증된 완료와 조절 한도는 결과 확인으로 구분한다. 자동 DB graph 교체는 수행하지 않으며 handoff에는 재확인·직접 이동·중단 선택을 제공한다.

## 2. 행동과 관측의 연결

- `guide_command_id`: 새로운 조작 안내마다 생성. 동일한 유효 target을 기다리는 동안 유지한다.
- `step_id`: 현재 graph transition ID.
- `attempt_id`: 이 안내로 수행할 논리적 시도 ID. guide를 다시 읽는 횟수가 아니다.
- `user_action_event.event_id`: FE에서 실제 행동 발생 시 생성하고 재전송 시 유지한다.
- `accessibility_tree.observation_id`: 실제 수집한 관측의 ID. 같은 snapshot 재전송 시 유지한다.
- `occurred_at_ms`, `captured_at_ms` 또는 `captured_at`: FE의 같은 시간 기준에서 행동과 수집 순서를 확인한다.

행동은 pending guide ID·target node ID·action 종류가 일치해야 한다. bounds가 전달되면 원래 안내 bounds와도 일치해야 한다. `observe_only`, `manual_override`, 다른 guide의 행동은 현재 target의 실패 증거가 아니다. event ID가 없는 구형 입력은 guide ID로 중복을 억제한다. guide ID 또는 target ID가 없는 행동으로 설정 변경 완료를 선언하지 않는다.

pending attempt에는 원래 target의 의미 식별자, window, bounds, 기준값, 관측 ID/시각, 수신한 행동의 식별자를 저장한다. 새로운 관측이 들어올 때마다 원래 기준값을 덮어쓰지 않는다. 행동 없이 값이 먼저 바뀌거나 target binding이 달라지면 기존 guide를 재사용하지 않는다.

Backend checkpoint schema는 `2`다. pending attempt·처리한 event hash·실패 target·복구 횟수를 저장하고 복원한다. 구버전 checkpoint는 기존 호환성 검사에서 거절하므로 새 실행을 시작해야 한다. graph 정의 변경도 graph hash를 바꾼다.

## 3. Verification

| 결과 | 요구하는 근거 | 실패 횟수 증가 |
| --- | --- | --- |
| `WAITING_FOR_ACTION` | 해당 guide에 귀속된 실제 행동 없음 | 아니오 |
| `WAITING_FOR_OBSERVATION` | 행동은 있으나 관측이 오래됐거나 아직 안정되지 않음 | 아니오 |
| `UNKNOWN` | 해당 target의 값·상태를 확인할 수 없음, 관측 시간 초과 등 | 아니오 |
| `NO_CHANGE` | 귀속된 행동 이후 안정된 두 관측에서 전이 조건 불충족, 원래 state 유지 | 해당 attempt 한 번 |
| `WRONG_STATE` | 귀속된 행동 이후 안정된 관측에서 다른 알려진 state 확인 | 해당 attempt 한 번 |
| `SUCCESS` | graph에 선언된 전이 predicate 충족 | 아니오 |
| task 완료 | `SUCCESS`이고 해당 전이가 `completes_task=True` | 아니오 |

실패 판단은 서로 다른 관측 두 개가 모두 행동 이후에 수집되고, 두 관측 간격이 기본 500ms 이상이며 fingerprint가 같을 때 가능하다. 행동 없이 같은 화면이 반복되거나 UI가 1px 움직였다는 사실만으로 실패를 만들지 않는다. 안정화 대기 500ms 안의 관측으로 복구 예산을 소진하지 않는다. 시각이 누락된 구형 입력으로 시간 기반 실패를 단정하지 않는다.

일반 화면 전이는 사용자가 수동으로 예상 화면에 도달해도 `SUCCESS / observed_progress / count_attempt=False`로 현재 위치를 갱신한다. 이것은 직전 guide 실행 성공을 입증한 데이터가 아니다. 완료 전이는 이렇게 자동 통과시키지 않는다.

글자 확대 graph의 predicate는 `target_value_increased`다. verifier도 matcher와 같은 selector·의미 식별자·window를 사용한다. 후보가 유일하고 기준값과 현재값이 있으며, 귀속된 drag 이후 값이 증가해야 성공한다. 밝기·음량·화면 크기 slider나 재사용된 node ID의 다른 control은 근거가 될 수 없다. `S5_FONT_SIZE_CHANGED`는 `verification_only=True`이며 제목만 보고 분류하지 않는다.

Backend는 진행도가 마지막이라는 이유로 완료를 덮어쓰지 않는다. 현재 verifier는 귀속된 행동 이후 동일 slider 값과 Android `FONT_SCALE`의 목표 방향 변화를 함께 요구한다. 기준값·설정값이 없으면 UNKNOWN이며 기술 결과는 UNVERIFIABLE로 남긴다. 최대/최소에서는 같은 방향 조작을 중단한다. 기술 결과와 사용자 만족은 별도 축으로 저장하는 코드가 있고, adapter는 완료/조절 한도와 복구/안전 차단을 구분하며, 결과 확인도 최종 task 성공과 구분한다. [결과 흐름](./goal-progress-result-flow.md).

## 4. UI 선택

### 4.1 먼저 통과해야 하는 조건

- visible·enabled, 양의 면적을 가진 실제 bounds.
- transition 또는 state가 허용한 package.
- active window ID가 전달되면 그 window에 속한 AX node만 정규화.
- action과 role 일치. `drag`는 slider, `tap/menu_item`은 menu item 또는 button, 설정 앱 열기는 허용 launcher의 app icon.
- 실패 target 제외 목록에 없음.
- selector의 `excluded_keywords`에 해당하지 않음.

기하학적 형태를 생성하거나 text bounds를 임의 확대하지 않는다. 기존 AX parent/child 근거에 따른 실제 control bounds를 사용한다. window 정보가 없는 구형 입력은 window 분리가 불가능하다. window가 명시됐는데 소속을 입증할 수 없는 보조 Vision node는 해당 window의 조작 대상으로 사용하지 않는다. 가려짐·hit-test·좌표 변환은 실제 기기 검증이 추가로 필요하다.

### 4.2 후보 비교

`Transition.selector`는 `synonym_keys`, `package_candidates`, `resource_ids`, `context_keywords`, `excluded_keywords`, `allow_unlabelled_in_context`를 가진다. `synonym_keys`가 가리키는 **전달 graph의** `synonyms`를 runtime이 읽는다.

| 근거 | 현재 점수 |
| --- | --- |
| selector의 resource ID 정확 일치 | 1.0 |
| 이름 또는 graph 동의어 정확 일치 | 0.9 |
| 완전한 단어/구 단위 selector 일치. 짧은 일반 별칭은 제목 정확 일치만 허용 | 0.8 |
| 이름 없는 유일한 control + 명시된 화면 문맥 | 0.8 |
| hard filter를 통과한 일반 UI의 language similarity | 최대 0.79 |

현재 채택 기준은 0.7, 1·2위 격차 하한은 0.05다. 같은 이름·같은 근거의 두 버튼이면 입력 순서와 관계없이 `ambiguous_target`을 반환한다. 명시된 우선 경로가 모호하면 낮은 우선순위 경로를 조용히 선택하지 않는다.

slider라는 이유만으로 0.82를 주던 규칙은 제거했다. 이름 없는 slider는 명시된 font-size 문맥에서 해당 역할의 control이 유일할 때만 후보가 된다. 이름이 `밝기`인 slider를 그 문맥으로 구제하지 않는다. slider에는 similarity를 통한 의미 조건 우회도 적용하지 않는다.

점수는 실측 성공 확률이 아니다. 임계값·격차는 초기 정책값이며 검증 데이터로 조정해야 한다. Backend의 기존 `state_confidence`에는 classifier 점수를 넣고, UI 점수는 `metadata.match_result.confidence`에 둔다. `score_semantics=heuristic_scores_not_probabilities`로 의미를 명시한다.

## 5. Re-plan과 제한

| strategy | 동작 |
| --- | --- |
| `continue` | 현재 graph의 다음 단계 안내 |
| `wait_for_action` | 같은 유효 guide를 유지하며 행동 대기 |
| `reobserve` | 최신 AX 관측 요청, 필요 시 기존 동의 경계의 Vision fallback |
| `retry_target` | 확인된 실패 후 같은 target에 새 attempt 발급 |
| `alternate_transition` | graph가 허용한 다른 target 또는 transition 선택 |
| `backend_reselection` | 조작 안내 중단, graph 재선택 필요 사유 반환 |

경로 선택은 `Transition.priority` 오름차순이다. 같은 priority는 transition ID로 일관되게 정렬한다. UI 점수가 높은 경로를 무조건 먼저 고르지 않는다. MVP는 Display 경로 10, Accessibility 대체 경로 20이다. 모호하지 않은 유효 후보가 없을 때만 다음 경로를 확인한다.

실패 집계 키는 `transition_id + target_key`다. target key는 package·resource ID·role·정규화된 이름을 해시한 값이다. tree index와 bounds가 바뀌어도 같은 의미의 target 제외는 유지한다. 동일 의미의 복수 control을 구별할 근거가 없으면 보수적으로 함께 제외한다. 다른 transition의 동일 node ID까지 전역 제외하지 않는다.

기본 정책은 target별 실제 실패 2회 후 제외다. 복구 모듈 `runtime/recovery.py`는 충분히 간격을 둔 새 관측 최대 3회 또는 5초 이후 사용자 확인을 기다린다. 중복/안정화 중 관측은 이 예산을 소비하지 않고 실제 실패와도 분리된다. 실제 실패 6회 또는 제외된 대상 외 경로 소진은 Backend handoff로 전달하되 작업을 실패로 종료하지 않는다. `RuntimePolicy`로 graph별 설정한다. 행동이 없는 정상 대기는 실패·복구 예산을 소비하지 않는다. target이 없는 guide를 한 번 반환했다고 제외를 해제하지 않는다. 새 실행 또는 명시적 graph 재시작에서만 초기화한다. 같은 graph 실행 중 임의 해제는 없다.

## 6. 승인과 로그

Vision은 1회 분석마다 별도 동의 ID를 발급·검증한다. FE 캡처 전 확인, 마스킹 실패 중단, BE 요청별 binding은 [Vision fallback 정책](./ai-agent-vision-fallback.md)을 따른다. Vision 허용은 아래 설정 변경 승인과 독립적이다.

위험한 변경은 유효한 target을 고른 뒤 승인을 요청한다. target을 못 찾았는데 먼저 변경 승인을 받지 않는다. 승인된 같은 조작 안내를 다시 표시할 때 Backend가 checkpoint의 guide·target·action·state·bounds·승인 만료를 확인한다. target이 바뀌면 재승인이 필요하고, 새 관측 ID/수집 시각만 달라진 것은 화면 변경으로 취급하지 않는다.

진단 trace v2에는 session/request/graph/observation/guide/attempt/action의 hash 연결과 검증 상태를 남긴다. 원문 화면 label을 로그에 추가하지 않는다. 이 로그는 여전히 제한된 메모리 진단 로그이며 AI2 학습용 영속 event store를 대신하지 않는다. Backend checkpoint는 runtime 복원용이고 AI2의 정답 라벨이 아니다.

## 7. FE 전달 형식과 적용 경계

```json
{
  "accessibility_tree": {
    "observation_id": "observation-uuid",
    "captured_at_ms": 1789196402000,
    "active_window_id": 42,
    "package_name": "com.android.settings",
    "screen_width": 1080,
    "screen_height": 2400,
    "nodes": []
  },
  "user_action_event": {
    "event_id": "action-uuid",
    "guide_command_id": "guide-uuid",
    "action_type": "drag_completed",
    "target_node_id": "node_0_4",
    "occurred_at_ms": 1789196401000
  }
}
```

위 예시는 공통 전송 필드 설명용이다. 실제 검증에는 target을 포함한 새 nodes가 필요하다. node에는 `window_id`를 전달한다. Frontend snapshot에 관측 ID와 window ID를 추가하고 event factory에 event ID를 추가했다. 기존 scroll/manual/observe 이벤트의 시각은 요청 전송 시점 대신 기록 시점에 보관하며, 재관측 요청은 저장된 snapshot 재전송 대신 실제 수집을 요청한다.

**현재 FE 연결과 남은 검증:** `ClickAccessibilityService`가 실제 접근성 tap/drag를 수집하고 `GuidedActionTracker`가 session/episode/guide/target에 귀속한다. `FONT_SCALE` 수집도 코드에 있다. 화면 변화만으로 행동을 만들지 않으며 node ID·resource·window·bounds·시각 일치가 필요하다. 실제 OEM 이벤트·RangeInfo·화면 재배치에서의 행동 누락/오귀속은 실기기 검증 대상이다. 연결 코드가 있다는 사실과 실기기 end-to-end 완료를 구분한다. [2026-10-10 재검증](./reviews/2026-10-10-ui-highlight-revalidation.md).

## 8. 2026-10-11 구조 기반 판단·복구 계약

화면 제목, 메뉴 제목, 설명, 컨트롤을 정규화 시 구분한다. classifier는 상위 상태 후보와 제목 근거를 보존하고 모호하거나 제목이 충돌하면 보류한다. 이전 위치의 연속성보다 현재 제목을 우선하여 단계 건너뛰기·수동 이동을 처리한다. 일시적인 UNKNOWN은 관측된 진행 경로를 지우지 않는다. `화면`을 `배경화면`에 매칭하지 않으며 부모 행의 실제 좌표를 유지한다.

`render_policy.recovery`와 `metadata.recovery`는 `schema_version=ai1-recovery.v1`, `decision`, `reason_code`, `next_action`, `waiting_for`, `recoverable`, `attempts`, `retry_after_ms`, `options`를 포함한다. `reason_code`는 화면 밖 대상, 화면/대상 모호성, 관측 부족·오래됨, 조작 결과 미확인, 행동 이벤트 누락, 경로 소진 등을 구분한다. 자유 텍스트 원인을 저장하지 않는다.

FE는 `waiting_for=user`이면 목표를 유지하면서 화면 다시 확인·직접 이동·중지를 제공한다. 직접 이동은 창 접기이며 자동 조작을 실행하지 않는다. 다시 확인은 새 관측과 `manual_override`를 보내 관측 재시도 예산만 초기화하고 실패 대상·실제 조작 실패 기록을 유지한다. 화면 밖 대상은 읽을 수 있는 scrollable 목록이 있고 대상 이름도 보이지 않을 때만 수동 탐색으로 처리한다. 제목만 있고 컨트롤이 없거나 동점인 경우에는 좌표를 만들지 않는다.

`recovery_state`와 `state_candidates`는 checkpoint에 함께 저장한다. 구 checkpoint의 새 필드는 기본값으로 복원한다. 진단은 허용된 원인 enum과 hash 처리한 후보 ID를 남긴다. 선택적으로 `ReplayRecorder`를 주입하여 구조·허용 UI 어휘만 남긴 관측을 내보낼 수 있다. 저장/업로드는 기본 비활성이고 캡처 결과를 정답으로 자동 채택하지 않는다. [재현 실행법과 한계](../AI-Agent/data/evaluation/runtime_recovery/README.md).

## 9. 회귀 검증

- `AI-Agent/tests/test_runtime_reliability.py`: 미조작 반복·1px 이동, 행동 중복/다른 guide, 관측 순서, 실패 제외 유지·node ID 변경, 경로 priority, 동점, role/package/window, graph 동의어, resource selector, false-DONE와 실제 완료.
- `Backend/tests/test_runtime_evidence.py`: HTTP 변환·checkpoint 복원·중복 행동·false-DONE 차단·새 관측과 승인 유지.
- 기존 engine flow/API/session/safety/privacy 테스트도 함께 실행한다.

`docs/reviews/evidence/agent_review_repros.py`는 수정 전 결함을 재현한 역사적 증거다. 수정 후 통과해야 하는 회귀 테스트로 사용하지 않는다. 계정 삭제 동시성 테스트의 기존 409 기대는 수정 전 커밋에서도 401을 반환했다. 현행 owner 인증 검사에 맞춰 401과 session/checkpoint/turn 삭제를 검증한다.

### 2026-09-12 당시 수정 검증 결과 (역사적 기록)

- AI-Agent 전체: **79 passed**.
- Backend 전체: **109 passed**. 기존 Starlette TestClient 경고 1건.
- 변경한 Java 6개 파일: Java compiler parser로 문법 확인. Android SDK가 없어 Android 타입 검사·앱 빌드·실기기 검증은 실행하지 못했다.
- `git diff --check` 통과. OpenAPI를 현재 앱 계약으로 재생성했다. 기존 구현의 voice-queue API가 이전 artifact에서 누락돼 있어 이번 재생성에 함께 포함됐다.
