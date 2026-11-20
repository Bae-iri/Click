# 설정 앱: LLM 계획 vs 태스크 그래프 비교

최신 develop `a4ebe36`을 반영한 작업 폴더를 기반으로 한다. 정부24 Planner 파일럿은 제거했고
검토용 소스만 `experiment/gov24-planner-v1-archive` 브랜치에 보관했다.
기존 정부24 기능과 그 최신 수정은 유지한다.

## 비교하는 것

| 방식 | 앱 | 계획 | 실행·검증 |
|---|---|---|---|
| graph | 클릭 그래프 실험 (`com.click.settingsgraph`) | 기존 승인 Task Graph | 공통 Jev·Overlay·네이티브 검증 |
| llm | 클릭 LLM 실험 (`com.click.settingsllm`) | Gemini 3.8 Flash가 하위 목표 순서 결정 | 같은 Jev·Overlay·네이티브 검증 |

안전한 원자 동작·대상 식별·완료 조건 템플릿은 두 방식이 공유한다.
LLM에는 해당 작업에서 허용된 하위 목표 이름과 현재 확인된 목표 후보를 주고, 기존 그래프의
전이 연결·순서·transition ID는 보내지 않는다. 목록은 불투명한 ID 순으로 정렬해 전달한다.
LLM이 순서를 정하면 현재 하위 목표에 해당하는 승인 동작만 Jev 후보에 남긴다.
LLM이 새로운 좌표·조작 코드·성공 조건을 만들거나 허용 범위를 늘리지는 못한다.
따라서 **공통 안전 동작 집합 안에서 고정 경로와 LLM의 목표 순서를 비교하는 실험**이다.
자유로운 GUI 계획의 모든 가능성을 비교하는 실험은 아니다.

각 세션/에피소드의 방식은 체크포인트에 고정한다. 중간에 바뀐 클라이언트 선언으로 방식을 바꾸지 않는다.
LLM 실패 시 그래프 방식으로 대체하지 않는다. 단순 대기·UNKNOWN으로 계획을 다시 쓰지 않고,
서로 다른 조작의 확인된 실패가 두 번이면 완료 목표를 보존한 채 재계획을 요청한다.
계획 호출은 에피소드당 최대 3회, 계획 길이는 최대 8개다.

## 시험 요청

* 휴대폰 글자 크기 키워줘 / 줄여줘
* 휴대폰 화면 밝기 높여줘 / 낮춰줘
* 미디어 음량 높여줘 / 낮춰줘

먼저 글자 크기 증가 하나를 두 앱에서 같은 조건으로 비교한 뒤 밝기·음량으로 넓힌다.
두 실험 앱은 기존 클릭의 승인된 설정 작업도 그대로 지원한다. 배경화면 변경 같은 직접 진행 안내는
대상 화면까지 안내하며, 사진 선택·최종 적용은 사용자가 한다. LLM도 같은 승인 동작 목록에서 계획한다.
숫자 변화로 직접 비교하는 기본 측정 목표는 위 여섯 가지이며, 직접 진행 안내는 별도로 기록한다.

## 빠른 실행과 비교 보고서

Backend 폴더에서 아래 명령으로 실험 환경·서버·실제 Gemini 계획 생성을 점검한다.
기존 두 APK를 USB 기기에 설치하려면 `-Install`을 추가한다.
Gemini가 실패하면 `LLM_NOT_READY`와 종료 코드 1을 반환한다. 서버 정상만으로 LLM 정상이라고 표시하지 않는다.

```powershell
cd C:\click_server\Click\Backend
.\scripts\start-settings-experiment.ps1 -Install
```

실기기에서 두 앱을 실행한 뒤 같은 조건의 결과를 JSON과 표로 내보낸다.

```powershell
python -X utf8 scripts/settings_experiment_log.py --compare --output-dir instance/settings-experiment/device-comparison
```

`comparison.md`를 열면 목표별 성공·미완료·API 장애·지연·토큰을 나란히 확인할 수 있다.
`comparison.json`은 동일 조건 그룹과 API 장애가 없었던 시험의 결과도 따로 제공한다.
모델 이름을 모르는 예전 로그나 모델·시작값·시작 화면·코드가 다른 시험은 같은 조건으로 비교하지 않는다.
잘못된 계획과 공급자 장애를 별도로 센다. LLM 실패 시 그래프로 대체하지 않는다.

휴대폰 없이 실행 경로와 제공자 연결을 비교하는 재생 실험도 가능하다.
재생은 화면·사용자 조작·설정값을 모의로 제공하며 실제 폰의 성공을 뜻하지 않는다.

```powershell
# 외부 API 없이: 여섯 목표 × 두 방식 × 순서 교대 2회 = 24회 통합 검증
python -X utf8 scripts/run_settings_comparison.py --providers controlled --all-goals --repeat 2 --output-dir instance/settings-experiment/controlled-comparison

# 실제 Jev/Gemini 사용: 같은 글자 크기 증가 화면을 두 방식에서 재생
python -X utf8 scripts/run_settings_comparison.py --providers live --output-dir instance/settings-experiment/live-comparison
```

두 명령은 각각 별도 폴더에 보고서·진단 추적을 저장한다. 라이브 모드가 API 장애로 진행하지 못하면 종료 코드 1을 반환한다.
통제 응답의 속도나 성공률은 LLM의 실제 성능 근거가 아니다.

이번 도입 판단의 LLM 모델은 **Gemini 3.8 Flash (`gemini-3.8-flash`)**다.
다른 모델의 연결 성공은 키·네트워크 진단용이며, 3.8의 성능이나 연결 성공으로 대체하지 않는다.
보고서 상단에 실제 시험 모델을 표시한다.

3.8 연결을 앱 코드와 분리해 확인하려면 다음 명령을 사용한다. 정적인 요청만 보내고 응답 원문·키는 출력하지 않는다.

```powershell
python -X utf8 scripts/probe_gemini_connection.py --model gemini-3.8-flash --api generate --shape schema --thinking low
python -X utf8 scripts/probe_gemini_connection.py --model gemini-3.8-flash --api stream --shape schema --thinking low
python -X utf8 scripts/probe_gemini_connection.py --model gemini-3.8-flash --api interactions --shape schema --thinking low
```

3.8은 `low/medium/high` 추론 설정을 지원하며 `minimal`은 지원하지 않는다.
공식 오류 분류상 429 일일 할당량과 503 과부하는 별개다. 태평양 자정의 일일 한도 초기화가 503 해소를 보장하지 않는다.
공식 근거: [3.8 모델](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash),
[오류 코드](https://ai.google.dev/gemini-api/docs/api-errors), [한도 초기화](https://ai.google.dev/gemini-api/docs/rate-limits).

## 준비

Backend 폴더에서 원본 `.env`의 Gemini/Jev 키만 실험 환경에 복사한다.
기존 데이터베이스는 건드리지 않고 `instance/settings-experiment/experiment.db`를 사용한다.
재실행해도 실험 DB의 암호화 키는 유지한다. API 키는 앱/APK/로그에 포함하지 않는다.

```powershell
cd C:\click_server\Click\Backend
$env:PYTHONPATH='C:\click_server\.backend-test-deps;C:\click_server\Click\AI-Agent\src'
python -X utf8 scripts/prepare_settings_experiment.py
python -X utf8 scripts/check_settings_planner.py
```

Gemini 모델 기본값은 `gemini-3.8-flash`다. 실험 `.env`의 `GEMINI_SETTINGS_MODEL`로 명시적으로 고정하며,
장애가 나도 다른 모델로 자동 변경하지 않는다. 모델 이름은 모든 시험 추적에 기록한다.
계획의 응답 제한은 8초이며 공통 턴 제한 안에서 동작한다.
실제 호출이 503/타임아웃이면 공급자 응답 문제로 따로 기록한다. 이 상태의 시험만으로 계획 품질을 비교하지 않는다.
`check_settings_planner.py`가 유효한 plan을 반환해야 실제 LLM 경로의 동작을 확인할 수 있다.
`--model gemini-3.5-flash`는 그 점검에만 다른 모델을 지정한다.
`--timeout 30`은 지연 진단용이며 앱의 8초 제한을 변경하지 않는다.

서버는 한 개만 실행한다. 이미 이 세션에서 시작한 8110 서버가 실행 중이면 다시 시작할 필요가 없다.

```powershell
python -X utf8 -m uvicorn app.main:app --env-file instance/settings-experiment/.env --host 127.0.0.1 --port 8110
```

두 앱은 같은 실험 DB와 서버를 사용한다. 실험 계정을 새로 등록하고 두 앱에서 같은 계정으로 로그인할 수 있다.
두 테스트 앱은 기존 앱과 별도로 설치된다. 카카오 대신 일반 회원가입/로그인을 사용한다.

빌드가 필요하면 Frontend 폴더에서 실행한다.

```powershell
.\scripts\build-settings-experiment.ps1
```

생성 파일은 `Frontend/app/build/outputs/settings-experiment/graph.apk`, `llm.apk`다.
USB 디버깅을 허용한 폰을 연결하고 PC PowerShell에서 실행한다.

빌드·서버 연결 점검 후 실제 기기 준비 검증은 Frontend에서 `./scripts/verify-settings-experiment.ps1`로 실행한다.
각 앱에서 설정 첫 화면부터 실제 글자 크기 증가와 `CHANGED` 판정을 확인하고 원래 값을 복원한다.
중단된 검증의 동일 요청만 정리하며 다른 작업이 실행 중이면 진행하지 않는다. 결과는
`Backend/instance/settings-experiment/device-readiness`에 저장한다. API 연결 성공만으로 기기 준비 완료를 표시하지 않는다.

```powershell
$adb='C:\click_server\.android-sdk\platform-tools\adb.exe'
& $adb devices
& $adb reverse tcp:8110 tcp:8110
& $adb install -r C:\click_server\Click\Frontend\app\build\outputs\settings-experiment\graph.apk
& $adb install -r C:\click_server\Click\Frontend\app\build\outputs\settings-experiment\llm.apk
```

접근성 서비스는 시험하는 앱 하나만 켠다. 아래 선택 명령은 다른 클릭 서비스들을 끄고 해당 앱만 켜며,
USB 연결과 자동 조작 설정을 두 방식에 동일하게 맞춘다. 기존 클릭과 같은 탐색·스크롤·조작 코드를 사용한다.
설정 변경에 필요한 승인과 직접 진행 경계도 기존 클릭과 같다. 수동 조건은 `-Manual`로 양쪽 모두 맞춘다.

```powershell
cd C:\click_server\Click\Frontend
.\scripts\select-settings-experiment.ps1 -Arm graph
# 그래프 시험을 끝내고 시작값을 복원한 뒤:
.\scripts\select-settings-experiment.ps1 -Arm llm
```

## 실제 진행

1. 비교할 설정값을 같은 시작값으로 맞추고 Android **설정 앱의 첫 화면**을 직접 연다.
2. ‘클릭 그래프 실험’ 접근성을 켜고 오버레이에서 ‘휴대폰 글자 크기 키워줘’를 요청한다.
3. 메뉴 탐색을 진행하고, 조절 승인 질문에 응답한다. 수동 조건에서는 표시된 항목과 슬라이더를 직접 조작한다.
4. 결과 확인 질문에 응답한다. 원래 설정값과 시작 화면으로 되돌린다.
5. 그래프 실험 접근성을 끄고 LLM 실험 접근성을 켠 뒤 같은 요청을 반복한다.

이후 순서를 바꾸어 LLM → 그래프로도 반복한다. 시작 화면·기기·설정값·네트워크 조건을 같게 유지한다.
자동 밝기처럼 외부에서 값이 바뀌는 조건도 두 방식에서 동일하게 둔다.
처음부터 조절 화면에 들어가 시험하면 메뉴를 찾는 계획의 차이를 평가할 수 없으므로 별도 조건으로 구분한다.

## 로그

PC의 새 PowerShell 터미널에서 실행한다. 기존 정부24 로그 명령 대신 아래 명령을 쓴다.

```powershell
cd C:\click_server\Click\Backend
$env:PYTHONPATH='C:\click_server\.backend-test-deps;C:\click_server\Click\AI-Agent\src'
python -X utf8 scripts/settings_experiment_log.py --follow
```

이미 실행 중인 로그 터미널은 `Ctrl+C`로 종료하고 같은 명령을 다시 실행한다.
시작 시 `event=watching`이 표시된다. 실행 전 요청 준비는 `event=goal_preparation`,
USB 연결 기기의 화면 검사 사유는 `event=device_preflight`로 표시된다.
`--follow`는 실행한 시점 이후 기록만 출력한다. 과거 기록도 보려면 `--follow --history`를 사용한다.
기기 로그는 PC에서 연결된 폰의 로컬 logcat을 읽으며 서버에 업로드하지 않는다.
화면을 확인하지 못한 경우 앱의 **설정 화면 열기**를 누르면 요청을 유지한 채 새 화면으로 다시 확인한다.
인증·민감 화면은 사용자 직접 진행 원칙을 유지한다.

요약은 다른 터미널에서 실행한다.

```powershell
python -X utf8 scripts/settings_experiment_log.py --summary
```

`--arm graph` 또는 `--arm llm`으로 필터링할 수 있다.
실험 RuntimeEvent는 기존 암호화·7일 보존 정책으로 저장한다. 로그에는 화면 원문·개인정보·노드 ID·좌표·키를 넣지 않는다.

| 필드 | 내용 |
|---|---|
| `arm`, `run_ref`, `goal_type` | 방식·시험 식별자·요청 목표 |
| `plan`, `subgoal_catalogue`, `active_subgoal` | 생성 계획·ID 의미·현재 목표 |
| `completed`, `skipped`, `revision` | 관측으로 완료한 목표·이미 지나간 목표·재계획 버전 |
| `planner` | 실제 호출 여부·모델·HTTP 오류·시간·토큰 수 |
| `planner.attempts` | 시간 예산 내 HTTP 재시도별 상태·API 오류 코드·분류된 원인 |
| `planning_attempts`, `provider_failures`, `planner_http_attempts` | 계획 시도 한도 사용량·공급자 장애 횟수·실제 HTTP 시도 수 |
| `jev` | 실제 호출 여부·후보 수·확신도·실패 이유 |
| `verification.action` | 안내와 결합된 실제 조작 이벤트 확인 |
| `verification.subgoal` | 화면·네이티브 값 등 조건 확인 |
| `verification.final` | 요청한 설정 변화의 기술적 검증 |
| `native_effect` | 허용된 설정값·슬라이더 값·범위의 전후 숫자 |
| `latest_user_result` | 현재 에피소드에 저장된 사용자 결과 응답; 요약 재실행으로 갱신 확인 |

판정은 `SUCCESS / WAITING / UNKNOWN / FAILED / BLOCKED`다.
안내를 표시하거나 조절을 승인한 사실만으로 행동 성공이 되지 않는다.
실제 조작 이벤트가 일치해도 값 변화를 확인하지 못하면 최종 목표는 UNKNOWN이다.
화면 정보가 없거나 로딩 중인 상태를 실패로 세지 않으며, 민감 화면에서는 외부 모델을 호출하지 않는다.
Gemini 일시 오류는 공유된 8초 계획 예산 안에서 지수 대기 후 최대 3회 HTTP 시도한다.
`Retry-After`/`RetryInfo`가 남은 예산을 넘으면 기다리거나 조기 재시도하지 않는다.
하루 요청 한도 초과와 인증·권한 오류는 자동 재시도하지 않는다.
공급자·통신 장애는 3회 계획 시도 한도를 소모하지 않으며, 검증된 계획 또는 잘못된 계획 응답만 소모한다.
`planner_calls`는 기존과 같이 플래너 호출 총수이므로 공급자 장애도 포함한다.
재계획 중 장애가 나면 기존 계획을 실행하지 않고 재계획 필요 상태를 유지한다.
`verification.final`은 기술적 효과이며 사용자 만족 응답과 구분한다.

요약은 **방식 + 동일 목표**별로 시험 수·네이티브 성공·UNKNOWN/미완료·차단·공급자 오류 시험·확인된 실패 횟수·
Planner/Jev 호출 수·계획 지연 시간을 보여준다. `observed_wall_ms`는 첫/마지막 보존 이벤트 사이의 벽시계 시간으로,
사용자 대기 시간도 포함한다. 런타임 계산 시간은 개별 로그의 `turn_latency_ms`를 본다.
공급자 오류·미완료 시험을 숨기지 않으며, 목표별 조건을 맞추지 않은 합산 성공률로 우열을 단정하지 않는다.

## 검증 범위

통제된 모델 응답으로 여섯 목표·두 방식의 메뉴 안내, 승인, 실제 조작과 네이티브 결과 확인을 검증한다.
체크포인트 재시작 후 방식/계획 유지, 모델 실패 시 대체 금지, 민감/빈 관측에서 호출 금지,
확인된 실패만 재계획에 반영하는 경우도 테스트한다.
자동 테스트 통과와 실기기 비교 완료는 별개다. 실제 기기에서 두 방식의 결과가 쌓인 뒤 구조를 결정한다.

## AI2 경로 축적과 재사용 (선택)

기본값(`CLICK_SETTINGS_ROUTE_REUSE_ENABLED=0`)에서는 위 A/B 규칙이 그대로 유지된다.
`=1`로 켜면 LLM 방식이 다음 순서로 동작한다. 순수 비교 실험에서는 켜지 않는다.

1. Backend가 AI2에 보존된 경로 중 **목표·기기 범위(scope)·기본 그래프·실행 코드·Jev 모델이 모두 같은** 검증 경로를 찾는다.
   현재 화면이 그 경로의 시작 화면이거나 경로 위에 있을 때만 쓴다. 맞으면 Gemini를 호출하지 않는다.
2. 맞는 경로가 없으면 Gemini가 계획한다. 실행 기록은 기존과 같이 AI2(`settings_graph_learning`)가 경로 초안으로 만든다.
3. Jev 화면 판단과 네이티브 성공 검증은 어떤 경로든 동일하게 거친다.
4. 재사용한 경로에서 확인된 실패가 두 번 나면 그 경로를 이번 에피소드에서 제외하고 Gemini로 재계획한다.
   AI2는 이 기록으로 해당 경로를 `QUARANTINED`로 격리해 다른 사용자에게 다시 내보내지 않는다.
5. 서로 다른 실행 `CLICK_SETTINGS_ROUTE_MIN_SUPPORT`회(기본 2회)에서 같은 경로가 검증되면 `VALIDATED`가 되어 재사용 대상이 된다.
   재사용 실행의 성공도 같은 경로의 근거로 쌓인다.

Gemini 공급자 장애나 계획 예산 소진 시에는 승인된 기본 그래프로 이어서 안내한다(`plan_source=approved_graph_fallback`).
이 실행은 경로 학습 근거로 쓰지 않으며, 비교 보고서에 `learned_route_trials`·`approved_graph_fallback_trials`로 따로 센다.

현재 상태 확인: `python -X utf8 scripts/settings_experiment_learning.py` → `routes`의 `CANDIDATE/VALIDATED/QUARANTINED`.

제한: 경로는 원천 실행 기록과 같은 동의·7일 보존 범위 안에서만 살아 있다. 동의 철회·만료 시 함께 사라진다.
장기 보존할 승인 그래프 버전으로 승격하는 절차(사람 검토, 보존 정책)는 아직 정하지 않았다.
