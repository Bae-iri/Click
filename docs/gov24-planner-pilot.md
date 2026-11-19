> 보관용 실험 브랜치: `experiment/gov24-planner-v1-archive`.
> `gov24-planner.v1` 로그를 생성했던 로컬 파일럿 소스를 검토할 수 있도록 복원했다.
> 원래 기반 커밋은 `2e284eb`이며 최신 develop의 정부24 수정은 포함하지 않는다.
> 실험 범위는 설정 앱으로 변경되었으므로 이 브랜치는 현재 develop에 병합할 대상이 아니다.
> 실제 Gemini 생성은 당시 타임아웃/503으로 성공 검증되지 않았다.

# 정부24 Planner / Jev 실기기 실험

이 실험은 Gemini `gemini-3.8-flash`가 작은 목표를 계획하고, Jev가 현재 공개 화면의 후보를 선택하면,
Click Overlay가 안내하고 사용자가 직접 누르는 구조다. 자동 탭은 하지 않는다.
기능은 등본·초본·전입신고·건축물대장 **안내 화면 찾기** 네 가지다.
기존 골 카탈로그의 승인 상태는 바꾸지 않는다. 새 앱의 `gov24_planner` 선언과 서버 실험 설정이 있어야
추가 세 기능을 서버가 안내로 연결한다. 실험 설정이 꺼져 있으면 기존 동작을 유지한다.

## 실행

이번 작업에서 준비한 로컬 실험 환경은 `Backend/instance/gov24-planner/.env`와 같은 폴더의 `pilot.db`다.
기존 DB의 내용을 복사하거나 변경하지 않으며 실험 계정은 별도로 로그인/등록한다.
원본 `.env`의 Gemini/Jev 키를 바꿨다면 다음 명령으로 실험 환경에 다시 반영한다. 기존 실험 DB의 암호화 키는 유지한다.

```powershell
python scripts/prepare_gov24_pilot.py --source-env .env
```

테스트 폰이 접속하는 Backend 환경에 다음을 설정하고 서버를 다시 시작한다.

```dotenv
CLICK_GOV24_GUIDE_ENABLED=1
CLICK_GOV24_PLANNER_ENABLED=1
GEMINI_API_KEY=<AI Studio에서 발급한 키>
TYPESAFE_API_KEY=<기존 Jev 키>
```

키는 서버에만 두고 앱이나 로그에 넣지 않는다. 모델은 3.8 Flash로 고정한다.
Backend 폴더에서 실제 연결을 확인할 수 있다.

```powershell
python scripts/check_gov24_planner.py --env-file .env
```

계획이 없으면 `diagnostic.reason`, `error_type`, `http_status`를 확인한다.
타임아웃이나 키 누락은 정상 계획 생성 성공을 뜻하지 않는다. 모델을 임의로 바꾸거나 규칙 계획으로 대체하지 않는다.
모델 접근과 최소 생성은 각각 `--check-model`, `--ping` 옵션으로 분리해서 진단할 수 있다.
계획 호출 제한은 25초, Jev는 8초이며 공개 안내 앱 요청 제한은 45초다.

새 앱을 테스트 서버 URL로 빌드한다. 로컬 서버는 기기에서 접근 가능한 주소가 필요하다.
USB 연결 시 `adb reverse tcp:8000 tcp:8000` 후 `http://127.0.0.1:8000`을 사용할 수 있다.
서버는 기존 프로젝트 실행 절차와 동일하게 실행한다.

Backend 폴더에서 서버를 실행하고 별도 터미널에서 로그를 연다.

```powershell
python -m uvicorn app.main:app --env-file instance/gov24-planner/.env --host 127.0.0.1 --port 8000
```

```powershell
.\gradlew.bat :app:assembleDebug '-PCLICK_APPLICATION_ID=com.click.gov24planner' '-PCLICK_API_BASE_URL=http://127.0.0.1:8000'
```

기본 빌드는 기존 서버 주소를 사용하므로, 로컬 `.env`만 수정해서는 원격 서버에 적용되지 않는다.
이 실험 코드를 반영한 Backend와 앱을 함께 사용한다.
테스트 앱은 `com.click.gov24planner` / ‘클릭 정부24 테스트’로 기존 앱과 함께 설치한다.
기존 앱과 서명이 달라도 삭제할 필요가 없다. 테스트 앱에서는 카카오 대신 이메일 회원가입/로그인을 사용한다.

## 확인할 요청

* 정부24 주민등록등본 발급 화면 찾아줘
* 정부24 주민등록초본 발급 화면 찾아줘
* 정부24 전입신고 화면 찾아줘
* 정부24 건축물대장 열람 화면 보여줘
* 주민등록등본 발급해줘 — 안내 화면 도착과 실제 발급 완료를 구분하는 대조 실험

Privacy Guard는 접근성 트리에서 현재 앱·현재 창·가시성·활성 상태를 확인하고,
허용된 공개 문구를 의미 ID로 바꾼다. 원문 트리·개인정보·입력값은 Gemini/Jev로 보내지 않는다.
인증·개인정보 화면에서는 외부 모델 호출 없이 안내를 종료한다. 안전한 공개 화면으로 돌아와 새 안내를 시작한다.

## 독립적인 세 단계 판정

| 판정 | 성공 근거 | 성공이라고 볼 수 없는 경우 |
|---|---|---|
| action | 안내 guide ID·관측 ID·node ID·시각이 사용자 클릭 이벤트와 일치 | 안내만 표시됨, 화면만 바뀜, 일치하지 않는 탭 |
| subgoal | 목표 메뉴 마커 또는 해당 민원 제목과 발급/신청 경계 버튼을 함께 관측 | 노드가 없음, 제목만 있음, 다른 서비스 제목만 있음 |
| final | ‘화면 찾기’ 요청에 해당 서비스 화면 도착 확인 | 발급·전입 신청 자체의 완료는 이 파일럿에서 검증하지 않음 |

실제 발급 요청은 `request_scope=task_completion`으로 기록하고, 안내 화면에 도착해도
`subgoal=SUCCESS`, `final=UNKNOWN`으로 남긴다. 세션의 최종 요청 성과도 `UNVERIFIABLE`로 저장한다.
‘화면 찾기’ 요청은 해당 화면 도착을 확인하면 `final=SUCCESS`다.

현재 실험의 조건 유형은 화면 진입(`screen_entry`), 요소 표시(허용된 `option_id`),
행동 확인(`action_performed`), 요청 완료(`task_complete`)다.
안드로이드 원본 노드에는 checked·progress·rangeMin/rangeMax 등도 있지만 정부24 공개 안내에는 내보내지 않는다.
상태 변경·슬라이더 값 변경·입력 완료 조건은 이번 파일럿 범위 밖이며 성공으로 추정하지 않는다.

## 다섯 판정 상태

| 상태 | 의미 | 다음 처리 |
|---|---|---|
| SUCCESS | 지정 조건을 관측으로 확인 | 다음 목표 또는 종료 |
| WAITING | 사용자 탭 또는 화면 갱신 대기 | 같은 목표 유지 |
| UNKNOWN | 관측 부족·노드 없음·모델 판단 불가 | 재관찰·사용자 확인, 그 자체로 재계획하지 않음 |
| FAILED | 연결된 탭 이후 충분히 식별되는 화면에서 목표 조건 불일치 | Jev 재판단, 같은 화면의 실패 후보 제외 |
| BLOCKED | 인증 경계·개인정보·호출/턴 한도 | 사용자 인계 또는 종료 |

연속된 확인 가능 실패가 두 번이면 LLM에 현재 상태·완료한 목표·실패 기록을 보내 남은 계획을 교체한다.
완료 목표와 관측 이력은 보존한다. 세션당 계획 생성은 최대 세 번, 전체 안내는 기존 24턴 제한을 따른다.
동일 화면에서 로딩이 계속되면 실패로 단정하지 않고 WAITING이다. 사용자가 직접 종료할 수 있다.

## 로그

Backend 폴더에서 테스트 서버와 **동일한 DB·암호화 키**를 사용하는 환경으로 실행한다.

```powershell
python scripts/gov24_experiment_log.py --env-file instance/gov24-planner/.env --follow
python scripts/gov24_experiment_log.py --env-file instance/gov24-planner/.env --session <세션ID>
```

한 행씩 JSON으로 출력한다. `run_ref`로 실행을 묶고 `step`으로 진행을 본다.

* `plan`, `active_subgoal`, `completed`, `revision`: 계획·현재 목표·완료 이력·재계획 버전
* `observation`: 앱·공개 의미 ID 목록·화면 signature·필요한 서비스 마커
* `planner`, `jev`: 실제 호출 여부·모델·지연 시간·선택·확신도·실패 이유
* `verification.action/subgoal/final`: 각각의 상태·조건 유형·판정 이유·근거·다음 처리
* `recent_failures`, `next`, `request_scope`: 실패 이력·실제 다음 처리·최종 요청 범위

응답의 `experiment`에도 같은 진단을 넣으며 기존 RuntimeEvent의 암호화 저장과 7일 보존을 사용한다.
표준 로거 `app.services.gov24_experiment`의 INFO 수준을 켜면 동일한 의미 진단을 서버 로그에서 볼 수 있다.
로그에는 키·원문 트리·사용자 입력·원문 노드 ID·좌표를 넣지 않는다.

AI-Agent2의 기존 경로 학습에는 다른 스키마(`gov24-planner.v1`)로 저장해서 섞이지 않게 한다.
이번 실험에서 Graph 캐싱이나 경험 추천까지 새로 구현하지 않는다.

## 실험의 한계와 판단 기준

계획 어휘는 ‘민원서비스/자주 찾는 서비스 진입 → 해당 민원 안내 진입’ 또는 직접 진입으로 제한했다.
임의 검색어 입력·스크롤·인증·제출을 생성하지 않는다. 새로운 앱 화면을 모두 처리하는 범용 에이전트가 아니다.
실제 기기에서 공개 제목이 클릭 가능한 컨테이너로만 노출되거나 허용 문구와 다르면 UNKNOWN이 될 수 있다.
특히 등본/초본 공통 화면에서 초본 전용 마커가 없으면 초본 성공이라고 추정하지 않는다.
이는 실기기에서 확인하고 공개 화면 판별을 보강할 지점이다.

네 기능마다 첫 실행·반복 실행, 안내 전 대기, 잘못된 메뉴 이동, AX 정보 누락, 인증 화면 진입을 시험한다.
완료율뿐 아니라 오성공 판정, UNKNOWN 비율, 복구 성공률, Planner/Jev 호출 수와 지연 시간을 비교한다.
단일 성공 사례로 구조 전체의 우월성을 판단하지 않는다.
