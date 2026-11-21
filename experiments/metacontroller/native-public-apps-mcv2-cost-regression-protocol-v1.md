# MCv2 무반응 조건 비용 회귀 — 사전 프로토콜 v1

## 목적과 범위

범용성을 첫째, 속도·비용을 둘째 목표로 유지한다. 이미 종료한 [MCv1 실제 앱 코호트](../../docs/reviews/2026-11-20-native-public-apps-b2/source-freeze.json)의 무반응 조건에서 관찰한 과도한 B 요청 비용을 대상으로, **미해결 실패가 남은 상태에서 일반 계획 요청도 같은 실패 예산에 포함하는 MCv2**를 검사한다. 새 성공 판정이나 앱별 생산 규칙을 추가하는 실험이 아니다. 비용이 줄었다는 이유만으로 B가 목표를 더 잘 달성했다고 판단하지 않는다.

이 프로토콜은 MCv1 전체 36회 종료와 facade 정지 뒤, 새 모델 요청 전에 작성한다. 새 실행 commit/tree·APK·환경·준비 자료와 이 문서까지 동결한 뒤에만 새 facade를 시작한다. 이전 코호트 원자료·메타데이터·프로토콜은 수정하지 않는다.

## 비교 조건과 연구 질문

- **A:** 생산 `CURRENT`, local diagnostic policy `current`.
- **B:** 생산 `META`, local diagnostic policy `mc_v2`.
- 같은 여섯 목표, 시작 상태, 공개 탐색 capability, 공통 완료 경로, 30초 예산과 외부 독립 oracle을 사용한다. B의 실패 예산 기록은 조작·완료·subgoal 진행 권한을 주지 않는다.

RQ1은 미해결 실패 뒤 같은 현재 경로에 대한 유료 계획 요청의 반복과 예산 우회가 줄었는지다. RQ2는 그 비용 제약이 실제 공동 성공과 실패 포함 시간에 어떤 상충을 보이는지다. RQ3은 이 현상이 세 앱 모두에서 관찰되는지다. 최종 진단값만으로 요청 순서·예산 초기화 원인 전체를 복원했다고 주장하지 않고, 별도 모델 없는 Controller/Meta 회귀 검사와 실제 요청 결합 결과를 함께 사용한다.

## 배정: 전체 일정 36회, 실행 대상 12회

[기존 실제 앱 카탈로그](native-public-apps-catalogue-v1.json)를 그대로 사용한다. Settings의 Display·Language & region, Clock의 Timer·Stopwatch, Files의 Recent·Documents다. 앱·목표·oracle·GUI 경로를 새 결과를 본 뒤 교체하지 않는다.

동일한 class/schema/측정 개정으로 전체 **3앱 × 2목표 × 3조건 × A/B = 36회·18쌍** 일정을 `conditionsJson=[]`로 먼저 고정한다. A/B 순서는 전체 일정의 기존 교차 순서를 유지한다. 그중 `accepted_no_effect`만 각 목표·팔 한 번씩, **12회·6쌍** 실행한다.

| 일정 부분 | 예정 수 | 이번 실행 상태 | 성능 분모 |
| --- | ---: | --- | ---: |
| accepted_no_effect | 12 | 사전 지정 실행 대상 | 팔별 6, 전체 12 |
| normal | 12 | NOT_RUN: 사전 지정 범위 밖 | 포함하지 않음 |
| delayed_transition | 12 | NOT_RUN: 사전 지정 범위 밖 | 포함하지 않음 |

미실행 24회는 실패·성공·누락된 계측 시행으로 만들지 않는다. 가짜 episode 행을 생성하지 않는다. 전체 일정 완료는 false이며, 12회 타깃 범위의 완료/유효성을 별도 표시한다. 기존 전체 36회 분석 기준을 완화하거나 기존 결과를 다시 계산하지 않는다. 타깃 범위에서 배정된 시행이 실제로 실패하거나 조작을 제안하지 않아 장애에 노출되지 못하면 해당 12회 분모에 남긴다.

실행 전에 전체 manifest의 episode ID에서 대상 12개와 NOT_RUN 24개를 명시적으로 유도해 등록한다. 타깃 시행의 재시도·중복 실행·성공 사례 선택은 하지 않는다. 안전 또는 계측 문제로 중단하면 미실행 타깃과 중단 이유를 남긴다.

## 출력 격리와 정책 출처

- `outputSuffix=mcv2-nofx-20261121-r1`
- `runId=native-public-apps-mcv2-nofx-20261121-r1`
- 장치 출력: `files/public-apps-meta-v1-mcv2-nofx-20261121-r1`
- class: `MetaControllerPublicAppsMatrixTest`
- schema: `click.native-public-apps.v1`
- 측정 개정: `equal_independent_observation_window_public_apps_v1`

날짜는 한국 시간 기준 2026-11-21이며 실제 UTC 시작·종료 시각을 별도 기록한다. 모든 readiness·guard·freeze·측정 명령에 같은 suffix와 run ID를 사용한다. suffix는 빈 문자열 또는 영숫자로 시작하는 1~48자의 ASCII 영숫자·`_`·`-`만 허용한다. 빈 값의 기존 기본 출력 동작은 유지하지만 이 새 실행에서는 사용하지 않는다. 기존 MCv1 장치/호스트 자료를 지우거나 같은 경로로 수집하지 않는다.

manifest의 `coordinator_policies`는 생산 Meta diagnostic에서 얻으며 기대값은 A=`current`, B=`mc_v2`다. 실제 episode의 `coordinator_policy`도 그 시행의 생산 diagnostic에서 복사한다. policy가 없거나 서로 다르면 숨기지 않고 출처/계측 문제로 보고한다.

다음 선택 필드만 타입을 보존해 기록한다. 누락·잘못된 타입을 false·0·권한으로 바꾸지 않는다.

| 필드 | 허용 값 | 의미의 한계 |
| --- | --- | --- |
| unresolved_failure_active | boolean | 마지막 진단 시점의 미해결 실패 상태 |
| unresolved_failure_planner_reservations | 정수 0..3 | 현재 미해결 epoch의 예약 수, 실제 공급자 호출 수 아님 |
| failure_epoch_ref | null 또는 소문자 24자리 hex | 원문 대신 사용하는 로컬 epoch 참조 |
| planner_route_ref | null 또는 소문자 24자리 hex | 로컬 현재 경로 참조, 목적 달성 증거 아님 |
| scoped_budget_reset_reason | initial / native_subgoal_advanced / target_context / user_refresh | 코드 경로의 마지막 초기화 이유, 실제 참여자 행동이나 전체 시계열의 증거 아님 |

기존 `failure_replan_reservations`, `recovery_count`, `progress_rescue_count`도 실제 API 시도로 세지 않는다. 모델 사용 context 전달은 정확히 결합된 실제 provider 시도와 닫힌 진단 필드로만 평가하며, 모델이 내용을 이해·사용했다는 증거로 확대하지 않는다.

## 준비와 실행 순서

1. 모델 없이 새 소스의 단위/계약 검사, 앱·test APK 빌드와 lint를 완료한다. 실제 앱 탐색은 접근성을 유지한 Session에서 수행한다. Files의 초기 Session 밖 관찰 실패를 생산 미지원으로 재사용하지 않는다.
2. 같은 suffix로 여섯 목표 readiness와 native guard를 다시 실행한다. 공개 실행 수락, 오래된 화면/다른 창 거절, 보호 입력 화면 거절과 fixture 불변을 통과해야 한다. 이전 디렉터리의 준비 파일을 복사해 대신 통과시키지 않는다.
3. 같은 suffix/run ID로 `runPublicAppsMatrix`, `conditionsJson=[]`, `phase=freeze`를 실행해 전체 36회 일정을 만든다. 새 카탈로그 해시는 기존 카탈로그와 같아야 한다.
4. 타깃 12/NOT_RUN 24 등록, source/tree·소스·APK·환경·준비·전체 manifest 해시를 모두 완성한다. 새 facade 미시작과 진행 중인 요청 없음도 확인한다. terminal 로그가 비어 있다는 사실만으로 진행 중 요청 0을 추정하지 않는다.
5. 새 빈 provider 로그 경로와 동일한 loopback 8769/격리 app ID로 facade를 시작한다. `phase=targeted-no-effect`, `conditionsJson=["accepted_no_effect"]`만 실행한다. 정상·지연 실험이나 비등록 live canary를 먼저 실행하지 않는다.
6. 새 디렉터리에서만 수집·직접 요청 감사·타깃 분석을 수행한다. 원본 native 시각과 모델/공급자 실패를 보존한다. source freeze 이후에는 관찰된 결과를 이유로 runtime·criteria를 수정하지 않는다.

## 성공·시간·안전 기준

[원래 공개 앱 프로토콜](native-public-apps-protocol-v1.md)의 기준을 그대로 사용한다. 주 지표는 예산 내 독립 목적 상태 700ms 안정, 현재 runtime verified, 알려진 음성 화면에서 false verified 0건, 미처리 native 조작 없음과 조작 후 750ms 대기가 함께 성립한 공동 성공이다. 실제 30,000ms 관찰을 유지하고 공동 성공만 조기 종료한다. terminal 표시나 실패 때문에 관찰을 빨리 끝내지 않는다.

첫 적격 native TAP에 수락 후 무효 장애를 적용한다. 장애 노출은 적격·수락·주입 기록으로 확인한다. 미노출도 배정 결과에 남기되 양팔 실제 노출 쌍을 별도로 제시한다. 타이머/알람 시작·설정값/파일 변경, 보호 화면 조작, 오래된 조작, 같은 조작의 무근거 자동 재시도를 허용하지 않는다. diagnostic이나 예산 초기화는 새 조작 권한이 아니다.

실패 포함 시간은 공동 성공 시 그 시각, 나머지는 30초다. 단순 도달·첫 안정 도달·첫 현재 검증·공동 성공·정리 뒤 상태를 구분하고 unknown을 false로 바꾸지 않는다. provider 실패·모델 이후 계약 거절은 실제 제품 결과로 포함한다. unknown 호출 수와 계측 문제는 별도 표시한다.

## 분석과 판정

대상 12회 배정 분모, 실제 실행 수, 팔별 6회와 앱별 2회, 정확한 시작 상태 쌍, 장애 노출, 공동 성공, 실패 포함 시간, 직접 결합된 실제 API 시도·unknown·확인 가능한 토큰을 보고한다. 토큰 사용량이 없는 요청을 0토큰으로 만들거나 예약 수를 청구/모델 호출 수로 치환하지 않는다. 전체 36회 완료가 아니라 타깃 12회 검사라는 표기를 유지한다.

안전 위반/false verified가 있으면 도입 NO_GO다. 계측·정책 출처·직접 결합이 불명확하면 해당 성능/비용 주장은 판정 불가로 표시한다. 정당한 provider 실패 자체를 무효 자료로 버리지 않는다. B의 공동 성공이 0이거나 A보다 적으면 B의 목표 달성 개선으로 판정하지 않는다. 실제 성공 증가와 비용 증가가 함께 보이면 상충을 그대로 제시한다. 비용만 줄어든 경우에는 좁은 비용 회귀 결과로만 기록한다.

이전 MCv1의 같은 무반응 12회와도 기술적으로 비교하되, 재실행·사후 표본 선택으로 분모를 바꾸지 않는다. 이전에는 정상 12회 이후 장애 조건을 실행했고 이번에는 무반응만 실행하므로 provider 준비/순서·시간·응답 변동까지 동일하게 통제한 인과 비교가 아니다. 목표당 1회인 이 타깃 검사로 통계적 우월, 모든 앱의 범용성, 중장년층 사용성 또는 기본 활성화 GO를 주장하지 않는다. 더 넓은 채택 판정은 별도 반복·앱·기기·사용자 평가에 남긴다.
