# A 통합본 Gemini/JeV 완료 경로 시간 실험 — 사전 프로토콜 v1

## 목적·변경 범위

A(`CURRENT`)를 메인으로 유지한다. 최신 원격2fc02c3(메모리 제안 차단 수정 포함)과 공통 완료우선/선택탭 관찰a89168d를 통합한 뒤, **초기 탐색 planner는 같은 Gemini**를 사용하고 terminal `result_only` 완료 증거 선택 경로만 비교한다. B는 실행하지 않는다. 사용자 설명을 확인해 SLM 실험이 아닌 기존 Gemini와 JeV 기반 통합 검증으로 정의한다.

앞서 작성한18회 반복 초안은 유료 실행 전 사용자의 JeV 속도 지시에 따라 이24회 설계로 대체했다. 신규18회는 실행하지 않았다. 첫 탐색6회는 별도 [선행 코호트](../../docs/reviews/2026-11-21-goal-first-completion/source-freeze.json)로 종료했고5/6·API12회·실패 포함 평균12,760.667ms였다. 첫6회를 새24회에 합치거나 무작위 대조군으로 부르지 않는다.

- Gemini 완료 정책: `APP_TASK_COMPLETION_PROVIDER=gemini`.
- JeV 완료 정책: `APP_TASK_COMPLETION_PROVIDER=jev`. 단일 긍정 화면/탭 이동 목표와 안전한 현재 증거 후보만 제한된 choice로 선택한다. 복합·입력·값 변경 등 정적 미지원 목표는 JeV 호출 전 기존 Gemini 경로를 사용한다. 명확한 조건을 만족하는 JeV 호출 후의 낮은 신뢰도/보류/오류에는 같은 요청에서 Gemini를 다시 부르지 않는다.
- JeV는 새 selector·조작·진행 상태를 생성하지 않는다. 실제 native 두 fresh frame≥350ms, 메뉴/보호/오래된화면 거절, 원래 conjunction 보존 등 완료 기준은 유지한다.
- JeV의2초는 I/O 예산과 늦은 응답 거절 기준이며, Gemini의 별도 killable worker와 같은 OS 종료 보장은 아니다. 실제 초과 시간도 숨기지 않는다.

## 신규24회 고정 배정

세 앱(Settings·Clock·Files)의 기존6개 정상 목표를 완료 정책2종×2회 반복한다. 목표는6개이며24개 독립 목표가 아니다. 동일 APK·runtime소스·카탈로그·시작 GUI·허용 capability·관찰/조작 기준을 사용한다. 전체24episode ID와 아래 블록 순서/정책을 최초 새 provider 호출 전에 동결한다.

| 블록 순서 | 반복 | 완료 정책 | runId | outputSuffix |
| --- | --- | --- | --- | --- |
| 1 | r1 | Gemini | native-a-time-gemini-20261121-r1 | a-time-gemini-20261121-r1 |
| 2 | r1 | JeV | native-a-time-jev-20261121-r1 | a-time-jev-20261121-r1 |
| 3 | r2 | JeV | native-a-time-jev-20261121-r2 | a-time-jev-20261121-r2 |
| 4 | r2 | Gemini | native-a-time-gemini-20261121-r2 | a-time-gemini-20261121-r2 |

각 블록은 기존카탈로그 순서 Settings Display·Language & region, Clock Timer·Stopwatch, Files Recent·Documents를 유지한다. 블록 순서를G→J→J→G로 교차하지만 과제 순서는 고정이며 무작위 실험이 아니다. 새 wrapper의6회 실제 episode를 사용하고 새 디렉터리/terminal원장을 블록별로 분리한다. 블록마다 drain과 원자료 수집 후 서버를 종료하고 다음 정책을 시작한다. 새 유료 canary나 선택적 실패 재실행은 없다.

Gemini모델은 gemini-3.8-flash, JeV는 설정된 고정버전jev-1.14.0이다. TypeSafe credential은 기존 설정을 메모리로 사용하고 출력/보관하지 않는다. JeV 설정 미비·후보 없음·중복·보류·시간초과·공급자 오류도 제품 결과로 남긴다. Clock처럼 기존 로컬 조건으로 완료돼 terminal 모델 호출이 없는 경우도 배정 결과에 포함하고 JeV 지연 근거로 사용하지 않는다.

## 성공·안전·계측

원래 기준대로30초 내 독립목적상태700ms안정, 현재runtime verified, native2프레임≥350ms, 조작후750ms대기·pending native0·false verified0을 모두 만족해야 공동 성공이다. 모형 confidence·계획 verified bit·클릭 수락만으로 완료 처리하지 않는다.

전체24회, 정책별12회, 블록별6회, 목표/반복별 paired 결과를 모두 제시한다. 준비·공급자 실패·모델 보류도 분모에 남긴다. 중단 시 미실행 배정을 unknown으로 보고한다. false verified·native guard 위반·설정값 변경이 발생하면 중단하고 남은 배정을 채우기 위해 재시도하지 않는다.

각 API request/task/server/model ref 직접 결합, 실제 provider 시도와 미호출/unknown, 정책과 실제 선택된 provider를 구분한다. 새24회의 모든ID·정책·출처·APK/환경/준비해시는 facade 시작 전에 고정하며 실행 중 runtime·기준·목표를 변경하지 않는다. 원래 과거 원자료와 해시는 수정하지 않는다.

## 시간·비용 분해

주 지표는 전체 배정 실패 포함 시간(공동 성공 시 공동 시각, 나머지30초)과 공동 성공이다. 성공 사례만 빨라져서 전체개선이라고 하지 않는다.

- 실제 요청 시도를 Gemini 탐색(goal_first/next_action), Gemini 완료(result_only), JeV 완료, provider 미호출로 분리한다. frontend API수와 모형 시도 수는 별개다.
- first receipt total_ms는 첫 API지연이며 사용자 첫 안내까지의 전체시간이 아니다.
- model_wait_ms 합/중앙값/범위/unknown, queue_ms, work_ms−model_wait_ms 비모델 잔여시간을 보고한다. worker·네트워크·모델 추론을 완전히 분리한 CPU측정으로 부르지 않는다.
- 첫 독립 안정도달→공동성공, 부호 있는 첫native검증 차이, 성공한 native2프레임 대기를 구분한다. oracle700ms와 native350ms 차이로 첫검증 차이는 음수일 수 있다.
- 첫 안정목적상태 이후 공동성공/마감 전 추가 native 조작 수를 재탐색 신호로 센다. 도달하지 않은 경우를0으로 위장하지 않는다.
- 실제 양쪽 완료 provider에 모두 노출된 동일 목표/반복 쌍도 기술적으로 제시한다. 노출이 없는 배정을 전체 분모에서 버리지 않는다. timeout·보류도 provider 지연 분포와 결과에 남긴다.
- 토큰 미확인을0으로 세거나 시도수를 청구 금액으로 바꾸지 않는다. 예산 후 provider drain 시간을30초에서 빼지 않는다.

## 판정

안전 위반0, 정확한24회 배정·정책 provenance, 직접결합과 호출unknown0, 동결소스 불변이 하드 gate다. 실패하면 성능 주장을 보류한다.

좁은 JeV 완료 경로를 다음 검증 대상으로 삼으려면, 관측 공동 성공이 Gemini보다 **전체와 각 앱 모두 줄지 않고**, 실패 포함 평균시간이 늘지 않고, 실제 총 provider 시도가 늘지 않아야 한다. 그중 시간의 엄격한 이득과 실제 양쪽 완료 provider에 노출된 동일 목표/반복 쌍4개 이상이 있어야 한다. 모두 통과해도 단일 기기 소표본의 다음 검증 진입일 뿐 범용/사용성 입증이 아니다. 실패·보류를 빨리 반환해 성공을 줄인 결과는 속도 개선으로 판단하지 않는다.

최초계획은24회에서 종료한다. 같은 목표/반복의 양쪽 실제 완료 모델 호출이 모두 있는 쌍이4개 미만이고 안전·계측 gate가 통과할 때만, 사전에 고정한 r3의Gemini6→JeV6을 전부 추가해 최대36회로 확장할 수 있다. 품질 회귀나 계측 문제를 유리한 표본으로 덮기 위한 확장은 하지 않는다. 확장12개의ID도 최초호출전에 별도 NOT_RUN/조건부일정으로 동결한다.

고정 앱/과제 순서, 단말/네트워크 워밍업·시간·공급자 변동이 남고 반복도 독립무작위 표본이 아니다. 초기6회와 새24/36회는 별도이며 보조 합산은 기술 통계로만 허용한다. 참여자는 없고 중장년층 사용성/다기기/모든 앱의 우월로 확대하지 않는다. 운영기본 A는 유지하며 JeV완료옵션은 실제품질/시간 결과와 설정 범위를 함께 기록한다.
