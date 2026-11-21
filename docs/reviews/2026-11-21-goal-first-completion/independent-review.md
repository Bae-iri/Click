# 독립 검토: 완료 우선 A 정상 탐색 6회

**원자료에서 공동 성공5/6·실제 API 시도12회, 목적 상태의 첫 안정 관찰 이후 추가 조작0건을 확인했다.** 완료한5회 모두 서로 다른 최신 native 두 프레임으로 검증했다. 다만 실제 공급자 시행에는 `goal_first/satisfied` 응답이 없었다. 따라서 목표를 먼저 평가하는 분기의 안전한 조작 중단은 모델 없는 구성요소 검사로 확인했고, 실제6회의 성과를 그 분기만의 효과로 해석할 수 없다.

이 검토는 추가 모델·ADB·빌드 실행 없이 기존 파일과 Git blob을 읽어 수행했다. 새 파일은 이 검토 문서뿐이며 원자료·소스·동결 기준은 수정하지 않았다.

## 출처와 배정

[동결](source-freeze.json)의 소스 commit은 `a89168d8e5f5f8b89009d70d4bd0a2d421a4e349`다. 소스57개 모두 해당 commit의 Git blob, 동결 해시, [종료 기록](cohort-closure.json), 감사 시점의 파일 해시가 같았다. 준비 자료4개(environment·manifest·native-component·unit-verification)도 동결·종료·현재 해시가 일치했다. 동결은2026-11-21 01:42:02 UTC, 종료는01:48:38 UTC다. 동결 시 facade 미시작·포트 닫힘·시도0회, 종료 시 drain idle을 기록했다.

[manifest](manifest.json)의 순서대로 Settings Display/Language & region, Clock Timer/Stopwatch, Files Recent/Documents 각 A·normal 한 번, 정확한6개 episode ID·task/package·팔·측정 context가 실제6행과 일치했다. 모두 setup_valid이며 B 시행은 없다. Clock 두 목표는 선택된 읽기 전용 탭이 새 관찰에 포함돼 과거와 시작 의미 지문이 다르다. 같은 GUI 준비 경로를 썼지만 동일한 표현의 paired 자료라고 볼 수 없다.

## 직접 요청 결합과 안전

생산 감사 함수를 원자료로 다시 계산한 JSON이 [저장된 요청 감사](request-audit.json)와 완전히 같았다. 별도로12개 고유 request_ref를 episode의 embedded trace·server terminal·audit association에 연결하고 task/server 참조, 호출 상태/수, 대기, planning mode, goal assessment, recovery/capability 메타데이터와 토큰 값·누락을 대조했다. 차이는 없었다. scoped client receipt12개와 completion event90개도 JSON으로 파싱됐다.

호출 unknown·미결합·orphan은0이다. 공급자 오류1건도 실패·비용 분모에 유지했다. 중간/최종 oracle unknown, 관측 false verified·guard 위반·예기치 않은 설정 변경은0건이고 정리 후 미처리 native action/게이트웨이 callback도 없었다. 실패한 Language 시행은 약30초의 독립 관찰을 유지했다. 성공은 독립 목적 상태700ms 안정·현재 runtime 검증·조작 후 대기 조건이 함께 성립한 시각이다.

## 실제 결과와 시간

| 목표 | 공동 성공 | API 시도 | 공동 성공 시각 | 안정 도달 뒤 공동 성공까지 | 안정 도달 뒤 추가 조작 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Display | 1 | 2 | 8,791ms | 3,692ms | 0 |
| Language & region | 0 | 2 | 미달 | 목적 상태 미도달 | 해당 없음 |
| Timer | 1 | 1 | 4,068ms | 0ms | 0 |
| Stopwatch | 1 | 1 | 4,159ms | 0ms | 0 |
| Recent | 1 | 3 | 13,343ms | 3,592ms | 0 |
| Documents | 1 | 3 | 16,203ms | 5,438ms | 0 |

실패 포함 평균은12,760.667ms다. Language는 첫 scroll 이후 두 번째 공급자 요청이 deadline에 도달했고 목표에 도달하지 못했다. 이를 계측 실패나 무효 시행으로 제거하지 않았다.

완료한5회의 첫/둘째 증거는 각각 다른 observation_ref, 같은 candidate_ref/window_ref, fresh 상태였고 두 번째 프레임의 경과는405·417·419·425·442ms였다(중앙값419ms). runtime 검증이 독립 안정 시각보다 먼저 찍힌 Clock의 두 사례는 독립 oracle이700ms, native 증거가최소350ms를 기다리는 차이로 설명되며 시각을 수정하거나 음수를 누락하지 않는다.

## 무엇이 실제로 작동했나

실제12개 요청은 `goal_first`9회와 `result_only`3회였다. `goal_first`는 `not_satisfied`8회, 공급자 실패로 assessment 없는1회였다. `satisfied`·`uncertain`의 실제 공급자 응답은0회다. Timer/Stopwatch는 첫 계획 응답이 미리 제공한 완료 조건을 실제 탭 선택 후 로컬에서 검증해 추가 API가 없었다. Display/Recent/Documents는 목적 도달 후 별도 result_only 요청으로 후보를 얻었다.

따라서 이번 실제 자료는 다섯 목적 도달 후 재탐색이 발생하지 않았고 완료 증거로 연결됐다는 관측을 지지한다. 그러나 pending 계획에서 `goal_first/satisfied`가 조작을 막는 실제 모델 분기가 이번6회에서 사용됐다고 주장하면 안 된다. 그 분기는 [모델 없는 native 구성요소 검사](native-component.json)의 별도 증거다. 구성요소9개는 모두 통과했고, 여섯 실제 목적 화면에서 한 scripted 요청·native 조작0·pending 계획 보존·두 프레임 검증을 확인했다. 클릭 가능한 drawer, 오래된 화면 복사, 보호 입력 세 음성 사례에서는 false verified0이었다. 이는 이미 도착한 상태의 코드 검사이며 탐색 성공률과 합치지 않는다.

## 남은 시간 병목

result_only 세 요청의 모델 대기는3,288·3,303·5,428ms, 합계12,019ms다. 목적 상태 이후3.6~5.4초의 추가 시간이 남아 있어 전체 완료 탐색이 즉시 끝난다고 말할 수 없다. 로컬 증거 확인0.4초보다 이 경로가 크다.

전체 요청의 model_wait 합계는48,731ms이며 `goal_first` 중앙값3,542ms, `result_only` 중앙값3,303ms였다. model_wait는 killable 공급자 프로세스 시작·새 HTTP 연결·공급자 처리·응답 파싱을 포함한 경계 시간으로 순수 Gemini 연산 시간과 같지 않다. 이번 loopback 실험에서 client work_ms와 model_wait_ms의 차이는5~16ms(중앙값7ms), queue_ms는0~1ms였으므로 이 자료만으로 UI/로컬 HTTP 처리에 수초가 든다고 해석할 근거는 없다. 실제 배포 네트워크의 왕복 시간까지 측정한 결과도 아니다.

토큰 관측 소계는 input15,928·output1,309·total17,453이며 각각1회 미확인이다. thinking은216 소계만 확인됐고10회는 미확인이다. 미확인을0으로 세거나 시도 수를 청구 금액으로 환산하지 않는다.

## 비교의 한계와 판정

이전 MCv1 정상 A의3/6·11회·실패 포함 평균18,739.333ms보다 현재는5/6·12회·12,760.667ms였다. 성공은2건 늘었고 실제 API는1회 늘었다. 공급자 실패도2→1건으로 달랐고 선택된 탭 관찰·공통 완료 코드·모델 응답·실행 시점이 함께 달라 개별 변경의 인과 효과나 통계적 우월을 입증하지 않는다. 목표당 한 번인 세 앱·단일 accountless 에뮬레이터·scripted approval이며 중장년층 참여자는 없다.

원자료·직접 결합·동결·증거에서 이번6회의5/6 성공 수치를 무효로 만들 계측 문제는 발견하지 못했다. **좁은 A 완료 처리 개선 후보를 지지하지만, 전체 범용성·모든 기기/앱·사용성 GO를 뜻하지 않는다.** B 재도입 근거도 아니다. 다음 시간 비교는 동일 모델·목표·안전 기준과 실패 분모를 유지하며 공급자 변동을 포함한 반복으로 판단해야 한다.
