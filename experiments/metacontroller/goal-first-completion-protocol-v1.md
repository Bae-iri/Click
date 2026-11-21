# 완료 우선 처리와 지연 검증 — 사전 프로토콜 v1

## 목적과 범위

운영 기본값 A(`CURRENT`)를 유지하고 B를 운영에서 폐기한 결정을 보존한다. 이번 변경은 공통 완료 처리이며 B 재도입 실험이 아니다. 목적 상태를 확인하는 과정이 모든 subgoal의 검증 이후로 밀려 실제 목적 화면에서 재탐색하던 문제를 대상으로 한다. 기존 계획 요청 한 번에서 `goal_first` 평가를 수행하고, 현재 공개 증거가 충분하면 조작 없이 완료 후보를 반환한다. 앱별 정답 경로·별도 reflection 모델·완료 기준 완화를 추가하지 않는다.

로컬에 유효한 후보가 있으면 새 모델 요청에 앞서 native 상태를 확인한다. 진행 중 모델 응답이 있어도 기존 후보를 읽기 전용으로 확인할 수 있고, 완료 검증 후 도착한 응답이 다시 조작을 제안하지 못해야 한다. 성공은 항상 현재 native 증거로 판단하며 모델의 `satisfied` 선언은 성공이 아니다.

## 단계 1: 모델 없는 실제 앱 구성요소 검증

새 `MetaControllerGoalFirstNativeTest#probeGoalFirstNative`를 명시적으로 실행한다. 전용 계정 없는 에뮬레이터에서 실제 Settings·Clock·Files의 기존 여섯 목적 화면에 도달한 뒤, pending 계획을 그대로 가진 생산 Controller와 실제 Gateway를 사용한다. 장치 내부 loopback scripted HTTP는 요청 하나에 현재 목적 상태의 후보만 반환하며 외부 모델은 호출하지 않는다. 이 단계는 이미 도착한 상태의 구성요소 검사이며 탐색 성공률 실험으로 세지 않는다.

여섯 양성 사례는 goal_first 한 번·추가 native 조작0·pending plan 불변·동일 후보에 대한 두 fresh native frame≥350ms·runtime verified를 요구한다. 음성 사례는 Files drawer의 클릭 가능한 메뉴 표시, 오래된 실제 화면 복사, 실제 보호 입력 화면이며 false verified0과 조작0을 요구한다. 공개 GUI 준비는 접근성을 활성화한 같은 Session에서 한다. 전용8769에 ADB reverse나 다른 listener가 있으면 먼저 정리하고 이 검사 중 외부 facade를 실행하지 않는다.

단위 검사에는 중간 화면의 일반 탐색을 같은 요청 안에서 계속하는 경우, 후보/응답의 조작·진행 변경 거절, stale/private/window/target 변경, 검증 이후 재탐색 금지, 모델 대기 중 기존 후보의 로컬 확인, 구형 서버 capability가 없을 때 기존 wire 형식 유지를 포함한다.

## 단계 2: 실제 공급자 A 정상 탐색 6회

단계1과 관련 단위 검사·빌드·lint가 통과한 뒤 기존 `native-public-apps-catalogue-v1.json`을 그대로 사용한다. Settings Display/Language & region, Clock Timer/Stopwatch, Files Recent/Documents를 카탈로그 순서로 A만 한 번씩 실행한다. native 시작 상태·허용 탐색·외부 독립 oracle·30초 관찰·700ms 안정·조작 후750ms 대기는 기존 실제 앱 프로토콜과 같다. native episode 코드를 재사용하며 기존 동결 harness는 수정하지 않는다.

- runId: `native-goal-first-a-normal-20261121-r1`
- outputSuffix: `goal-first-a-normal-20261121-r1`
- 새 class의 live method: `runGoalFirstNavigation6`
- 최초 `goalFirstNavigationFreeze=true`는 일정만 생성하고 모델을 호출하지 않는다.
- 실제 실행은 `goalFirstNavigationExperiment=true`로6회만 한 번 실행한다.
- endpoint: 격리 loopback8769, 기존 native 공개 앱 capability mask.
- 모델: `gemini-3.8-flash`, 같은 공급자 시도1회·약9초 요청 예산.

실제 요청 전 전체6회 일정, source commit/tree·변경 소스·APK·환경·모델 없는 준비 자료·이 프로토콜·카탈로그 해시를 완성하고 facade 미시작/포트 닫힘을 확인한다. 새 terminal 원장을 사용한다. 실행 중 결과를 이유로 소스·기준·목표·시작 상태를 바꾸거나 실패 시행을 재실행하지 않는다. 추가 유료 canary와 B 시행은 하지 않는다.

## 지표와 판정

6회 배정·실행·준비 실패·공급자 실패·독립 안정 목적 도달·현재 runtime verified·공동 성공·안전 위반을 모두 보고한다. 공동 성공과 실패 포함 시간(성공 시 공동 시각, 실패 시30초)은 원래 기준을 유지한다. 첫 독립 안정 목적 시각과 첫 native 검증/공동 성공 시각, 목적 상태의 첫 안정 관찰 뒤 추가 조작 수, 정확히 결합된 API 시도와 모델 대기/미확인 사용량을 보고한다. oracle의 안정 시각은 이미700ms를 포함하므로 native350ms 검증보다 늦을 수 있다. 음수 차이를 계산 오류로 숨기거나 CPU 계산 시간으로 해석하지 않는다.

한 번의 계획 요청이 goal_first로 완료 여부도 평가했는지, `satisfied`가 안전한 현재 완료 후보와 연결되고 추가 조작을 막았는지 확인한다. 모델 대기 중 native 검증 시간은 별도 모델 없는 동시성 검사로 검증하며 실제 공급자 시행과 혼합하지 않는다.

안전 위반/false verified/출처 불명/미결합 요청이 있으면 성능 주장은 보류한다. 모델 없는 구성요소 통과는 결정적 코드 경로의 증거일 뿐 모델 정확도·전체 범용성의 증거가 아니다. 실제6회는 작고 반복이 없으므로 통계적 우월이나 모든 앱·기기·중장년층 사용성 GO를 주장하지 않는다.

선택된 비클릭 native 탭을 읽기 전용 증거로 관찰하도록 확장했으므로, 같은 물리 시작 화면이어도 이전 버전과 의미 지문이 달라질 수 있다. 카탈로그의 같은 GUI 시작 경로·선택 상태·독립 음성 대조를 유지하고, 지문이 다른 시행을 정확히 같은 representation의 paired 비교라고 하지 않는다. 이전 MCv1 정상 A6회(공동 성공3/6·API11회)와 동일 목표·GUI 시작 조건을 비교하되, 공통 코드·모델 응답·시점이 함께 달라 인과 비교가 아닌 기술 비교로 명시한다. 성공을 줄여 호출/시간만 줄이면 개선으로 판정하지 않는다. 원래36회/12회 원자료와 해시는 수정하지 않는다. 유료 실행은 사전 지정6회에서 종료하고 관찰된 한계를 기록한다.
