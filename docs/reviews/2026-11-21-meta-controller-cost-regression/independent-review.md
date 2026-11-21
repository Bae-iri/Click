# 독립 검토: MCv2 무반응 12회

**원자료에서 A 공동 성공 0/6·API 시도 7회, B 0/6·19회를 확인했다. 전체 비용 개선이나 B 도입 GO를 뒷받침하지 않는다.** B의 Files Documents 한 시행은 목적 상태에 도달했지만 runtime 검증이 없어 공동 성공으로 세지 않았다.

모델·ADB·빌드를 다시 실행하지 않고 [원자료](episodes.jsonl), [공급자 기록](provider-terminal.jsonl), [직접 요청 감사](request-audit.json), [요약](summary.json), [동결](source-freeze.json)·[종료 기록](cohort-closure.json)과 동결 commit `8ef653aacff1ba17fd5d034bc20daa9e00cd65c3`의 Git blob을 대조했다. 원자료·메타데이터·생산 소스는 수정하지 않았다.

## 일정·출처·계측

- 전체 36회 일정에서 사전 지정 무반응 12개의 ID·task/package·팔·팔 순서가 실제 12행과 정확히 일치한다. 정상/지연 24회는 NOT_RUN이며 실패 분모에 넣지 않는다. 타깃 12회 완료와 전체 36회 미완료를 구분한 요약이 적절하다.
- 6쌍 모두 양팔의 시작 의미 지문·measurement context가 같고 setup이 유효하다. manifest와 실제 행의 정책은 A=`current`, B=`mc_v2`다.
- 소스 47개는 동결 commit의 Git blob 해시, 동결 기록, 종료 시점 해시가 모두 일치한다. 준비 자료 7개도 동결·종료·현재 보관 파일의 해시가 일치한다. facade 미시작 상태의 동결 시각은 `2026-11-20T16:10:38.437751Z`, 종료는 `16:18:48.691645Z`다.
- 26개 고유 request가 episode client trace·server terminal·audit association에 직접 대응한다. request/task/server 연결과 호출 수·상태·대기, 토큰 값/누락, 닫힌 recovery/capability 필드 복사에 차이가 없다. 호출 unknown·미결합은 0건이다.
- 이번 scoped client receipt 26개와 completion event 129개는 모두 JSON으로 파싱된다. 이전 수집의 누락 `previous.jsonl` 진단 줄이 이 scoped 파일에 섞인 문제는 없다.

모든 시행이 공동 성공 없이 30초 관찰을 유지했다. 예산 뒤 poll을 예산 내 결과로 센 사례는 없고 중간/최종 oracle unknown도 0이었다. false verified·native guard 위반·예기치 않은 설정 변경·privacy guard 거절은 0건이며 정리 후 미처리 native action도 0건이다. 이는 제한된 실제 앱 환경의 관측 결과이지 모든 앱의 안전 보장은 아니다.

## 성과와 비용

| 항목 | A | B |
| --- | ---: | ---: |
| 배정·실행 | 6 | 6 |
| 공동 성공 | 0 | 0 |
| 독립 목적 도달 | 0 | 1 |
| 실제 API 시도 | 7 | 19 |
| 실제 장애 노출 | 5 | 6 |
| 실패 포함 평균 | 30초 | 30초 |

현재 B는 A보다 12회 더 호출했다. 이전 MCv1 무반응 B의 17회보다도 2회, 11.8% 많다. 과거/현재의 네 시행 모두 유효한 같은 시작 상태와 실제 장애 노출을 요구한 비교는 4개 목표이며 B 요청은 10→12회, 공동 성공은 0→0이다. 전체 12회 결과와 이 좁은 매칭 분모를 혼합하면 안 된다. A Language의 과거 미노출과 현재 A Recent의 공급자 실패 등 응답 변동이 있어 인과 효과나 통계적 우월을 주장할 수 없다.

공급자 실패도 배정 결과와 실제 API 시도에 남아 있다. 토큰 관측 소계는 input 33,749·output 2,343·total 37,263이며 각각 3회 미확인이다. thinking은 1,171의 소계만 확인됐고 21회는 미확인이다. 미확인을 0으로 계산하거나 API 시도 수를 청구 금액으로 환산하지 않는다.

## 실패 예산이 적용된 범위

B의 실패 예약 합계는 10회이고, 직접 결합된 `mc_v2 / accepted_action_unconfirmed` context 요청도 10회다. attempt 분포는 1이 6회, 2가 2회, 3이 2회다. 각 B 시행의 task 전체 실패 예약은 1 또는 3이며 상한을 넘은 값은 없다. 최종 reset 이유는 5개가 초기 `target_context`, Files Documents만 `native_subgoal_advanced`였다.

이는 미해결 실패의 일반 요청까지 예약에 포함한 기록과 일치한다. 다만 실패 예약 3회는 **시행 전체의 모든 API를 3회로 제한한다는 뜻이 아니다.** 첫 요청, 새 native subgoal 진전으로 실패 범위가 해제된 뒤의 요청, 별도로 허용된 result_only를 구분해야 한다. 최종 진단만으로 매 순간의 route/epoch 초기화 전체를 재구성하거나 모든 가능한 코드 경로가 기기에서 검증됐다고 주장하지 않는다.

## Files Documents B: 6회인데 완료가 아닌 이유

이 시행은 **next_action 5회 + result_only 1회**다. 요청 순서는 첫 일반 요청, recovery context attempt 1·2·3, recovery context 없는 일반 요청, result_only다. task 실패 예약은 3회로 유지됐고, 마지막 scoped 상태는 새 native subgoal 진전으로 해제됐다. 같은 앱 열기나 이미 충족된 상태로 예산을 누출한 증거, result_only 반복 루프의 증거로 해석하지 않는다.

| native 동작 | 시행 시작 후 |
| --- | ---: |
| Show roots 무효 수락 | 3,453ms |
| Show roots 실제 수락 | 14,833ms |
| Documents 실제 수락 | 18,969ms |
| Show roots 실제 수락 | 22,664ms |
| Documents 실제 수락 | 29,198ms |

독립 oracle의 첫 안정 목적 상태는 **20,204ms**였지만 첫 runtime verified 시각은 null이다. 처음 다섯 응답에는 수락된 완료 후보가 없었고, 목적 도달 후에도 계획과 탐색이 이어졌다. 마지막 result_only에서만 후보가 도착했으며 provider 대기 7,599ms 뒤, 관찰 예산이 끝난 상태에서 `discarded/not_observable`로 버려졌다. 예산 후 provider 정리 대기는 별도 7,689ms로 기록됐다. 실제 기록에 `verified`나 fresh 두 프레임 완료가 없으므로 도달을 공동 성공으로 올리지 않은 판정이 맞다.

이 사례는 전체 호출 증가의 잔여 경로가 실패 예약 초과뿐 아니라 **목적 도달 뒤 완료 후보 부재·재탐색·늦은 후보**에도 있음을 보여준다. 특정 요청을 제거하거나 마지막 후보가 일찍 왔다면 반드시 성공했을 것이라는 반사실은 이 자료로 확인할 수 없다. 원래 후보 내용과 각 native subgoal의 모든 상세 조건을 재구성하는 근거도 부족하다.

## 판정

[README](README.md)의 핵심 수치와 NO_GO 결론에 모순을 발견하지 못했다. 코드 수준의 예산 보완과 실제 총비용 개선은 구분해야 한다. 이번 비용 회귀에서는 총비용 감소와 공동 성공 개선을 입증하지 못했다. 단일 기기·세 앱·목표당 한 번·참여자 0명이고 정상/지연 MCv2 성능도 실행하지 않았다. 추가 runtime 변경이나 유료 시행 없이 이 12회 결과를 종료하는 것이 적절하다.
