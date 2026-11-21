# 갤럭시에서 요청 완료와 동작 실패를 구분하는 실험

## 측정 전 고정한 조건

기준판은 `50a603b`다. 중간 동작의 예상 화면이 틀리면 실제 목적지에 도착해도 동작 검증이 끝날 때까지 결과를 확인하지 못한다는 가설을 세웠다. 두 번째 가설은 수락한 동작의 관찰 시간 초과를 ‘미확인’으로 기록하면, 실제 실행 거부와 구분하면서 동일 동작의 반복을 제한할 수 있다는 것이다.

한국어 삼성 설정의 디스플레이·소리 및 진동·알림을 사용한다. 각 목적지에서 정상 도착, 중간 단서 예측이 틀린 도착, 5초 후 도착, 제자리, 다른 설정으로 이동, 실행 거부를 두 버전에서 비교한다. 기기의 전경 Activity를 정답으로 사용한다. 두 버전에 같은 최종 제목 조건을 미리 주고, 실행 수락과 화면 이동 시점만 통제한다. 화면 관찰, 중간 판정, 완료 확인과 기록은 앱의 실제 코드를 사용한다.

적용 기준은 예측이 틀린 도착의 확인 지연 또는 누락이 감소하고, 제자리·다른 화면·거부에서 완료 오판이 없으며, 명시적 거부를 실패로 계속 남기는 것이다. 목적지 검출, 버튼을 눌러 사용자가 최종 확인했는지, 개별 동작의 확인 여부를 각각 측정한다. 전체 자연어 요청의 성공률은 별도 지표다.

설정 실험과 별개로 삼성 시계에서 타이머와 스톱워치 탭을 실제로 눌러 본다. 스톱워치 본문에 있는 네이티브 뷰의 리소스로 정답을 확인하고, 선택된 탭 조건의 기기 판정을 두 화면에서 각각 두 번 읽는다. 시계 검사는 일반화 범위를 살피는 독립 사례다.

## 설정에서 확인한 결과

USB로 연결한 Galaxy Note20 5G(SM-N981N, Android 13)에서 기준판과 개선판을 차례로 실행했다. 1회 비교에 버전당 18건이며, 시간은 승인부터 기기 판정까지다. 설정 실험은 일반 앱 안내 컨트롤러에 승인된 동작 한 건과 중간 예측을 제공했다. 실행기의 수락 여부와 Android 설정 화면의 이동 시점을 통제했다. 설정값 변경이나 모델 호출은 없었다. 아래 시간은 사전에 정한 첫 비교의 중앙값이다.

| 상황 | 건수/버전 | 기기 완료 확인, 기준 → 개선 | 확인 시간, 기준 → 개선 | 동작 기록, 기준 → 개선 |
|---|---:|---:|---:|---|
| 정상 도착 | 3 | 3 → 3 | 1.37초 → 1.36초 | 확인 → 확인 |
| 예상한 중간 항목 없이 목적지 도착 | 3 | 3 → 3 | 5.81초 → 1.36초 | 실패 → 미확인 |
| 5.8초 뒤에 목적지 이동 시작 | 3 | 3 → 3 | 7.43초 → 7.52초 | 실패 → 미확인 |
| 제자리 | 3 | 0 → 0 | — | 실패 → 미확인 |
| 요청과 다른 화면 | 3 | 0 → 0 | — | 실패 → 미확인 |
| 실행기가 즉시 거부 | 3 | 0 → 0 | — | 실패 → 실패 |

두 버전 모두 실제 도착 9건을 감지했고 미도착 9건에서 완료를 제시한 횟수는 0회였다. 개선판은 수락 후 결과를 입증하지 못한 12건과 명시적 거부 3건을 구분했다. 모든 시도에서 실행기에 전달한 동작은 1회였고 사용자 최종 확인은 0회였다. 늦은 도착 3건에서는 두 버전 모두 먼저 미확인 안내를 띄웠고, 이후 도착 이벤트로 완료 조건을 다시 확인했다.

탭 수정을 포함한 빌드와 최종 수동 확인 버튼까지 포함한 빌드에서 각각 18건을 다시 실행했다. 두 재검증의 완료·미확인·거부 건수는 첫 개선판과 같았다. 중간 예측이 틀린 도착의 중앙값은 각각 1.359초와 1.358초였다. 원자료의 `baseline`, `candidate`, `final`, `release`는 이 순서의 네 실행이다. [설정 72건의 개별 기록](settings.csv)

## 삼성 시계에서 추가로 찾은 완료 누락

스톱워치가 열렸는데 기준판은 연속 두 프레임에서 모두 `NOT_MET`를 냈다. 실제 뷰에는 이름 ‘스톱워치’와 `selected=true`가 함께 있었다. 뷰 클래스는 일반 `LinearLayout`이고 탭 묶음을 나타내는 `tabs` 리소스는 두 단계 위에 있었다. 기존 관찰 코드는 개별 뷰의 클래스와 리소스만 검사해 이 항목의 역할을 잃었다.

수정판은 명시적인 탭 묶음에서 이름이 있는 형제 항목이 둘 이상이고 정확히 하나가 선택된 경우 각 항목의 탭 역할을 보존한다. 선택 여부는 해당 항목에 Android가 제공한 값을 사용한다. 일반 목록과 복수 선택 그룹은 회귀 테스트에 포함했다. 시계 탭은 네이티브 클릭으로 이동했고 스톱워치 본문에 있는 시간·버튼 뷰를 독립 정답으로 사용했다. 타이머는 입력 보호 처리를 유지했고, 비교용으로 추가한 세계시각에서도 스톱워치 완료가 나오지 않았다.

| 실제로 열린 탭 | 기준판의 두 관찰 | 수정판의 두 관찰 | 최종 컨트롤러 결과 |
|---|---|---|---|
| 타이머 | 보호됨 × 2 | 보호됨 × 2 | 스톱워치 완료 없음 |
| 세계시각 | 미충족 × 2 | 미충족 × 2 | 스톱워치 완료 없음 |
| 스톱워치 | 미충족 × 2 | 충족 × 2 | 요청한 결과를 감지하고 사용자 확인 대기 |

마지막 프레임에서는 컨트롤러가 요청 결과를 확인했는지와 사용자가 최종 확인했는지도 각각 기록했다. 여섯 프레임은 탭 세 번의 관찰이다. [기준판·수정판 시계 관찰 기록](clock.csv)

## 앱에 적용한 판정

- 동작 반영을 기다리는 중에도 최종 조건을 검사한다. 같은 앱과 창에서 350ms 이상 떨어진 두 개의 새로운 관찰이 조건을 충족하면 사용자에게 결과 확인을 제시한다. 그동안 입증되지 않은 중간 단계는 그대로 두고 해당 경로를 학습하지 않는다.
- 실행기가 거부하면 `failed`, 실행기는 수락했지만 효과를 입증하지 못하면 `waiting`을 기록한다. 앱과 서버 양쪽에서 같은 화면·동작·대상의 두 미해결 시도는 반복 제안을 막는다.
- 모든 안내 단계를 마쳤어도 결과 증거가 부족하면 화면을 다시 읽는 선택을 먼저 제시한다. 사용자가 직접 결과를 확인했다고 명시할 수 있다.

## 재현 범위

설정 실험은 사용자의 목표와 최종 증거 조건을 미리 준 상태에서 판정기를 검증한다. 자연어 해석, 제안한 버튼의 정확도, Jev의 후보 선정, 인증·전송·결제 성공률은 이 수치에서 추정할 수 없다. 같은 기기에서 얻은 반복 실행은 독립 사용자 표본으로 합산하지 않았다.

최종 코드의 Android 단위 테스트는 1,454개 통과, 5개 건너뜀, 실패 0개였다. 백엔드의 `test_app_task*.py` 477개가 통과했다.

계측 진입점은 [GoalOutcomeDeviceTest.java](../../../Frontend/app/src/androidTest/java/com/click/service/accessibility/GoalOutcomeDeviceTest.java)에 있다. 기준판은 `50a603b`에 같은 계측 파일을 복사한다. 두 버전 모두 `Frontend`에서 아래처럼 별도 패키지로 빌드한 뒤 해당 앱과 테스트 APK를 같은 기기에 설치한다.

```sh
bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PCLICK_APPLICATION_ID=com.clueclick.outcomestudy
adb -d shell am instrument -w -e outcomeStudy candidate -e class 'com.click.service.accessibility.GoalOutcomeDeviceTest#compareOutcomesOnDevice' com.clueclick.outcomestudy.test/androidx.test.runner.AndroidJUnitRunner
adb -d shell am instrument -w -e clockOutcome true -e requireClockMatch true -e class 'com.click.service.accessibility.GoalOutcomeDeviceTest#inspectSelectedTabAfterRealClockNavigation' com.clueclick.outcomestudy.test/androidx.test.runner.AndroidJUnitRunner
```

기준판에서는 설정 계측에 `-e outcomeStudy baseline`을 사용하고 시계 계측의 `-e requireClockMatch true`를 생략한다. 결과는 실험 앱의 `files/outcomes-<arm>.json`과 `files/clock-outcomes.json`에 저장된다. 준비 단계에서 암시적 인텐트가 이전 화면을 재사용했고, 계측 셸의 인자 전달 문제도 발견했다. 두 시도는 전경 Activity 검사가 실패해 중단했다. 최종 비교에는 명시적인 Activity로 전환하고 모든 정답 검사를 통과한 실행을 사용했다.

## 범용 앱 안내의 완료 조건이 실제로 끊긴 지점

별도 실험 APK는 비교를 위해 완료 조건을 미리 넣었다. 갤럭시에 설치된 본앱이 실제 요청을 처리한 경로는 본앱에 남은 비식별 진단 이벤트와 서버 응답 수신 기록을 요청별로 연결해 조사했다. 관측 구간은 2026년 11월 21일 13:01~16:27 KST다. 화면·요청 원문과 기기 식별자는 수집하지 않았다.

1. 휴대폰이 사용자 요청과 현재 화면의 공개 요소를 `POST /api/v1/agent/app-guide/task`로 보낸다. 서버 플래너는 완료 근거 후보를 `completion_checks`로 제안한다. 단순한 목적지나 스위치 상태는 서버의 규칙으로도 후보를 만들 수 있다. [요청 생성](../../../Frontend/app/src/main/java/com/click/service/overlay/AppTaskController.java) · [서버 판정 경로](../../../Backend/app/services/app_task.py)
2. 서버가 모델의 화면 요소 ID를 앱 패키지·라벨·역할·상태와 연결하고 유효한 후보를 응답의 `completion.checks`에 넣는다. 메뉴에서 보이는 미래 목적지 후보는 `deferred_completion`으로도 전달할 수 있다. 개별 동작을 확인하는 `action.expectation`은 별도 필드다. [조건 정의](../../../Backend/app/schemas/app_task.py) · [후보 검증](../../../Backend/app/services/app_task_completion.py)
3. Android는 받은 후보를 해당 작업의 `AppTaskMemory.completion()`에 보관한다. 아직 기기 증거로 지지하지 않은 후보는 다음 요청의 `state.completion`에 싣지 않는다. 기기는 같은 앱과 창의 서로 다른 최신 관찰 두 개에서 조건이 350ms 이상 간격으로 맞는지 검사한다. 후보가 없으면 `candidate_missing`으로 비교를 건너뛴다. 기기가 근거를 확인한 뒤에도 사용자가 최종 확인해야 작업이 종료된다. [작업 메모리](../../../Frontend/app/src/main/java/com/click/service/overlay/AppTaskMemory.java) · [기기 판정](../../../Frontend/app/src/main/java/com/click/service/overlay/AppTaskController.java)

| 갤럭시 본앱 관측 | 건수 | 확인된 내용 |
|---|---:|---|
| 휴대폰의 서버 호출 기록 | 61 | 60건은 응답을 받았고 1건은 네트워크 오류였다. 수신한 60건 모두 목표와 다음 동작을 함께 묻는 `goal_first`였고 모델 참조값이 Gemini 3.8 Flash와 일치했다. |
| 응답의 `completion`에 조건이 0개 | 57 | 다음 동작 제안 43건, 모델 응답 기한 초과 9건, 사용자 입력 필요 4건, 결과 확인 제안 1건이었다. |
| 응답의 `completion`에 조건이 1개 | 3 | 1건은 응답이 돌아왔을 때 화면이 바뀌어 폐기됐다. 받은 조건을 채택한 2건 중 1건은 관측 구간의 비교 6회 모두 미충족이었고, 1건은 두 관찰로 기기 검증을 통과했다. |
| 기기가 최종 목표를 검사한 이벤트 | 343 | 309회는 현재 작업에 후보가 없어 비교를 시작하지 못했다. 나머지 34회는 일치 18회와 미충족 16회였다. 동일 작업의 반복 관찰이 포함된다. |

응답 건수는 해당 응답의 `completion` 필드를 센 값이다. 앞선 요청에서 이미 저장한 후보나 `deferred_completion`의 개수는 포함하지 않는다.

한 작업에서는 다음 동작을 네 번 제안받았고 네 응답 모두 `completion`이 비었다. 이 작업의 기기 검사 23회가 모두 `candidate_missing`이었다. 현재 구현은 하위 단계가 남아 있으면 다음 요청에서도 목표와 동작을 함께 묻는다. 모든 단계를 검증했고 최종 조건이 아직 기기에서 확인되지 않았을 때 완료 전용 요청을 보낸다. 기기에 남은 집계로는 Gemini가 처음부터 후보를 생략했는지, 서버가 어떤 후보를 걸렀는지 57건 각각의 원인까지 복원할 수 없다. 사용자가 실제로 목적을 달성했는지도 이 로그의 측정 항목에 없다.

### 로컬 Qwen에서 모델 응답부터 API 응답까지 재현

`2a0d433`의 범용 안내 API에 이전 계획과 완료 조건 없이 “시계에서 스톱워치 탭을 열어줘”를 입력했다. 개인 정보가 없는 모의 화면에 세계시각·스톱워치 탭을 제공하고, 실제 로컬 Qwen3-1.7B의 원래 구조화 응답과 서버의 최종 응답을 대조했다. 스톱워치가 선택된 입력에서 Qwen은 올바른 탭에 `selected=true`와 `checked=true`를 함께 붙였다. 스키마는 `checked`를 토글에만 허용하므로 서버가 `checks.index.checked`를 검증 오류로 기록하고 조건 전체를 보류했다. 목표와 동작을 함께 묻는 경로와 완료 전용 경로 모두 기기에 전달할 값이 `completion=null`이었다. 완료 전용 응답의 `status=confirm_result`만 보고 기기 검증 성공으로 집계하면 이 사례를 잘못 세게 된다.

추가로 같은 모델에 실제로 관찰한 선택지만 제시하는 형식을 격리 환경에서 비교했다. 완료 전용 호출은 스톱워치가 선택되었을 때 올바른 근거를 반환했고, 세계시각이 선택되었을 때는 서버가 완료를 보류했다. 반면 목표와 동작을 함께 물은 호출에서는 이미 스톱워치가 선택된 입력에 세계시각을 누르도록 제안했다. 이 비교는 로컬 Qwen의 공개 모의 입력에 대한 결과이며, 본앱의 Gemini 응답이 비었던 이유는 별도의 관측이다.

### 완료 조건을 미리 넣지 않은 수정 후 검증

갤럭시 Note20에서 “시계에서 스톱워치 탭을 열어줘”를 실행했다. 각 API 요청은 하위 단계와 완료 조건이 비어 있는 상태에서 시작했다. 기기는 실제 시계 화면을 평소와 같은 방식으로 읽고 USB로 연결한 개발 서버에 요청했다. 서버는 로컬 Qwen3-1.7B를 사용했다. [실기기 계측](../../../Frontend/app/src/androidTest/java/com/click/service/accessibility/GoalOutcomeDeviceTest.java)

| 확인 시점 | 서버 응답 | 기기에서 확인한 결과 |
| --- | --- | --- |
| 세계시각이 선택된 상태에서 다음 동작 요청 | 스톱워치 탭 누르기와 해당 탭의 `selected=true` 조건을 함께 반환 | Android가 응답과 동작 대상을 검증했다. 현재 화면에서는 조건이 `NOT_MET`였다. |
| 같은 화면에서 완료만 요청 | 완료 조건 없음 | 다른 탭의 선택으로 완료가 성립하지 않았다. |
| 제안받은 탭을 누른 뒤 | 첫 응답의 조건을 그대로 검사 | 스톱워치 본문의 시간·조작 요소를 별도로 확인했다. 500ms 간격으로 읽은 두 화면 모두 `MATCHED`였다. |
| 도착한 화면에서 이전 조건 없이 완료만 다시 요청 | 스톱워치 탭의 선택 조건 반환 | Android가 다시 받은 조건으로도 `MATCHED`를 확인했다. |

위 세계시각 실험을 두 번 실행해 두 번 모두 통과했다. 앞선 타이머 실험에서는 화면 전환 중 한 번 `protected_observation`이 반환되어 검증을 중단했다. 최종 계측은 실제 탭이 선택되고 기기가 화면을 공개 상태로 판별한 뒤에만 요청을 보낸다.

같은 API에 공개 모의 화면 두 쌍도 입력했다. 보관함 메뉴에서는 누를 항목과 미래 화면의 제목을 분리해 전송했고, 도착 후 보관함 제목으로 확인했다. “자동 동기화를 꺼줘”에서는 Qwen이 일반 이름인 “사용” 스위치의 `checked=false`만 제안했다. 서버가 해당 스위치를 구별하는 “자동 동기화” 제목까지 함께 전달했다. 스위치가 켜져 있으면 탭 동작을 제안했고 이미 꺼져 있으면 완료 확인으로 넘어갔다. [서버 회귀 검사](../../../Backend/tests/test_app_task_goal_grounding.py)

OpenRouter의 Qwen3.8-Flash 경로도 공개 모의 시계 화면으로 별도 검증했다. 이때 서버의 사전 판정을 꺼서 두 요청 모두 모델이 응답하게 했다. 선택 전에는 탭 동작과 `selected=true` 조건을 함께 받았고, 선택 후에는 같은 조건으로 완료 확인 응답을 받았다. 두 HTTP 응답의 `model` 값은 모두 `qwen/qwen3.8-flash`였다.

Gemini는 수정 전 본앱에 남아 있던 60개 응답에서 식별한 모델이다. 그 기록에는 원래 요청·화면이 없어 같은 입력으로 재실행한 성공률은 산출할 수 없다.

### 갤럭시에서 Qwen3.8-Flash로 재검증

갤럭시 Note20의 실제 시계 화면에서 위 실험을 **OpenRouter Qwen3.8-Flash**로 두 번 반복했다. 세계시각에서 시작해 “시계에서 스톱워치 탭을 열어줘”를 요청했다. 개발 서버는 `APP_TASK_PROVIDER=openrouter`, `APP_TASK_OPENROUTER_COMPACT=1`, `APP_TASK_COMPLETION_TRIAGE=0`으로 실행했다. 매 요청마다 Android가 읽은 공개 화면과 빈 작업 상태를 전송했다. 총 6건 모두 OpenRouter가 HTTP 200과 `model=qwen/qwen3.8-flash`를 반환했고, Android 계측도 6건의 모델명을 확인했다.

| 실기기 회차 | 세계시각에서 다음 동작 요청 | 세계시각에서 완료만 조회 | 스톱워치에서 완료만 조회 | Android의 실제 판정 |
| --- | --- | --- | --- | --- |
| 1 | Flash가 `tap`을 골랐다. 서버가 요청과 탭을 연결해 `selected=true` 조건을 함께 보냈다. | Flash가 근거 0개 반환 | Flash가 근거 1개 반환 | 누르기 전 `NOT_MET`, 누른 뒤 두 화면 모두 `MATCHED` |
| 2 | Flash가 최종 탭 선택인 `final_tab`을 골랐다. 같은 조건이 기기에 전달됐다. | Flash가 근거 0개 반환 | Flash가 근거 1개 반환 | 누르기 전 `NOT_MET`, 누른 뒤 두 화면 모두 `MATCHED` |

각 회차는 처음 받은 조건을 유지해서 확인하는 경우와, 도착한 화면에서 이전 조건 없이 다시 확인하는 경우를 모두 통과했다. 계측 실행 시간은 각각 7.383초와 11.203초였다. 이 시간에는 세 모델 호출, 실제 탭 이동, 두 화면 확인과 복원이 모두 들어간다. 같은 Flash 설정에서 보관함 메뉴와 자동 동기화 끄기의 공개 모의 화면도 전후 총 4회 호출해 검증했다.

재현할 때는 `Backend/.env` 또는 셸에 `OPENROUTER_API_KEY`를 설정하고 `Backend`에서 아래 서버를 실행한다. 서버는 루프백에서 시험용 사용자로 평소 앱 안내 경로를 호출하며, 실제 공급자 응답의 모델명을 검사한다. 별도 터미널에서 USB 포트를 연결하고 계측 인자에 기대하는 모델을 명시한다. [실험 서버](../../../Backend/scripts/serve_flash_clock_study.py) · [계측 코드](../../../Frontend/app/src/androidTest/java/com/click/service/accessibility/GoalOutcomeDeviceTest.java)

```sh
PYTHONPATH=.:../AI-Agent/src python -m scripts.serve_flash_clock_study
```

```sh
adb -d reverse tcp:18778 tcp:18778
adb -d shell am instrument -w -e clockPlanner true -e clockPlannerModel qwen/qwen3.8-flash -e class 'com.click.service.accessibility.GoalOutcomeDeviceTest#verifyUnseededClockPlannerContract' com.clueclick.outcomestudy.test/androidx.test.runner.AndroidJUnitRunner
```
