# MCv2 무반응 비용 회귀 결과

**복구 예산 우회는 수정했지만, 실제 전체 호출 비용은 개선되지 않았다. B 기본 활성화는 NO_GO다.** 세 앱·여섯 목표의 사전 지정 무반응 12회에서 A 공동 성공 0/6·API 시도 7회, B 0/6·19회였다. 이전 MCv1 같은 조건의 B17회보다 2회 많다. 일부 시행의 절감만으로 비용 개선을 주장하지 않는다.

실제 실행 소스는 **`8ef653aacff1ba17fd5d034bc20daa9e00cd65c3`**, 모델은 `gemini-3.8-flash`다. [source-freeze.json](source-freeze.json)은 2026-11-20 16:10:38 UTC에 facade가 꺼진 상태에서 소스·APK·준비·전체 일정을 고정했다. 한국 날짜는 11월21일이다. [종료 기록](cohort-closure.json)은 16:18:48 UTC에 소스·준비 해시 불변과 서버 idle을 확인했다. 이전 MCv1 원자료는 수정하지 않았다.

## 비교 결과

공동 성공은 30초 안에 독립 oracle의 목적 상태 700ms 안정, runtime의 fresh native 두 프레임 검증, 조작 후 대기·안전 조건을 함께 만족한 경우다. 실패 포함 시간은 성공 시 공동 시각, 실패 시 30초다. API 시도는 공급자 실패도 포함한 직접 결합된 요청 시도이며 청구액이 아니다.

| 무반응 조건 | 이전 MCv1 A | 이전 MCv1 B | 현재 MCv2 A | 현재 MCv2 B |
| --- | ---: | ---: | ---: | ---: |
| 배정·실행 | 6 | 6 | 6 | 6 |
| 공동 성공 | 0 | 0 | 0 | 0 |
| 독립 목적 상태 도달 | 0 | 0 | 0 | 1 |
| API 시도 | 6 | 17 | 7 | 19 |
| 실패 포함 평균 | 30초 | 30초 | 30초 | 30초 |
| 공급자 오류 | 1 | 2 | 1 | 2 |
| 실제 장애 노출 | 5 | 6 | 5 | 6 |

현재 B는 A보다 12회 더 호출했고, 이전 B보다 11.8% 많았다. **속도·성공 개선도 입증하지 못했다.** 단순 목적 도달 1건을 공동 성공으로 올리지 않았다. 원래 전체36회 중 정상/지연24회는 이번에 사전 지정 NOT_RUN이며 이 12회 분모의 실패로 넣지 않는다. 전체36회 완료도 false다.

| 목표 | 이전 B 시도 | 현재 B 시도 | 현재 B 공동 성공 |
| --- | ---: | ---: | ---: |
| Settings Display | 4 | 2 | 0 |
| Settings Language & region | 4 | 5 | 0 |
| Clock Timer | 2 | 2 | 0 |
| Clock Stopwatch | 2 | 2 | 0 |
| Files Recent | 3 | 2 | 0 |
| Files Documents | 2 | 6 | 0 |

같은 시작 상태이고 양팔이 실제 장애에 노출된 조건을 과거/현재 네 시행 모두에 요구하면 비교 가능한 목표는4개다. 이 범위도 B10→12회, 공동 성공0→0이다. 과거 A Language와 현재 A Recent의 공급자 오류로 장애 미노출이 생겨 나머지2개는 이 매칭 범위에서 제외했다. 전체 배정 결과에서는 그대로 유지했다. 모델 응답·순서·시간과 공통 완료 코드도 달라 특정 변경의 인과 효과나 통계적 우월을 주장하지 않는다.

## 무엇이 수정됐고 무엇이 남았나

[구현 설명](implementation.md)의 MCv2는 실패 뒤 일반 요청도 동일 경로당1회·task 전체 복구 예약3회에 포함한다. 스크롤 검증·같은 앱 다시 열기·이미 선택된 상태·이미 값이 있는 입력만으로 실패 예산을 초기화하지 않는다. 실제 새 native 요구 상태와 subgoal 진행, 명시적 사용자 화면 확인/대상 변경을 구분한다. 새 UI 버튼이나 별도 reflection 모델은 추가하지 않았다. 로컬 완료 확인은 계속 가능하다.

실제 B에서는 실패 예약10회와 `mc_v2/accepted_action_unconfirmed` context를 받은 공급자 시도10회가 일치했다. attempt1/2/3은6/2/2회였다. 마지막 진단상 각 task 예약은1 또는3이었다. 이는 이번 호출 기록과 회귀 검사에서 예산 적용을 확인한 것이며 모든 동작 경로를 실제 기기로 전수 검증한 주장은 아니다.

남은 핵심은 **목적 도달 뒤에도 완료 증거가 없어 재탐색하는 비용**이다. Files Documents B의 독립 안정 목적 상태는20,204ms였지만 현재 runtime 검증은 없었다. native 조작은 `Show roots` 무효 수락→`Show roots`→`Documents`→`Show roots`→`Documents`였고, API 요청은 next_action5회와 result_only1회였다. 복구 context를 가진 요청3회 뒤 새 native subgoal 진전으로 scoped 상태가 풀렸으며, task 예약 상한은3으로 유지됐다. result_only 요청이 반복된 사례가 아니다.

모든 초기 관찰에는 completion 후보가 없었다. 마지막 result_only는 공급자 대기7,599ms 뒤 도착했고, 관찰 예산이 끝나 `not_observable`로 버려졌다. 결과 화면에서의 불필요한 재탐색과 늦은 완료 후보가 결합했다. 이 자료만으로 특정 재탐색 요청을 제거하면 반드시 성공했을 것이라고 단정하지 않는다. 완료 기준을 낮춰 수치를 바꾸거나 이 결과를 본 뒤 원자료를 재분류하지 않았다.

## 계측·검증·한계

[요청 감사](request-audit.json)는26개 요청을 모두 직접 결합했고 호출 unknown과 orphan은0이다. 최종 oracle unknown·관측 false verified·native guard 위반·예기치 않은 설정 변경은0이다. 두 팔의 실제 장애 노출이 다른 시행도 보존했다. 알려진 total_tokens 소계는 A9,446(1회 미확인), B27,817(2회 미확인)이며 미확인을0으로 세거나 돈으로 환산하지 않는다.

Android 관련502개 검사와 빌드·lint가 통과했다. Backend 관련446개 검사 후 마지막 분석 수정은 해당21개 검사로 재확인했다. 첫 sandbox 실행의 로컬 socket bind 거절은 권한 있는 loopback 검사에서 재실행해 통과했고 [검증 기록](unit-verification.json)에 범위를 남겼다. 최종 native 준비6/6 및 guard는 모델 없이 통과했다. 이전 merged journal의 누락 파일 진단 문제는 이번 strict pull에서 디렉터리를 먼저 조회하고 모든 JSONL을 파싱해 방지했다. 원래 전체 journal과 이번 task26개 receipt·129개 completion event를 별도로 보존했다.

실제 app UI를 사용했지만 단일 accountless Android17 에뮬레이터·목표당1회·scripted approval이며 참여자는 없다. 중장년층 사용성, 실제 기기 전체, 정상/지연의 MCv2 성능은 이번 타깃 실험에서 평가하지 않았다. [독립 검토](independent-review.md), [사전 검토](pre-live-review.md), [통합 기록](integration-notes.md), [재현](reproduce.md)을 함께 읽어야 한다.

## 판정과 다음 방향

코드 수준의 실패 예산 보완은 완료했고, 실제 전체 비용 개선 판정은 **NO_GO**다. B 도입 역시 **NO_GO**다. 다음 우선순위는 초기 계획에서 검증 가능한 목적 상태를 별도로 유지하고 매 화면에서 조작 전에 확인해, 목적 도달 후 재탐색과 마지막 모델 의존을 줄이는 것이다. 완료는 기존 native 증거 기준으로 확인해야 한다. 앱별 정답 경로나 추가 모델을 늘려 성공 사례를 맞추지 않는다.

[앞으로의 구체적 우선순위](next-direction.md)에는 목적 상태 계약, 새 정보가 있는 관찰 중심 조정, 복구 가능한 상황/적절한 중단 상황의 사전 구분, 반복·새 앱·기기·사용자 검증을 적었다. 미구현 방향을 이번 변경의 성과로 세지 않는다. 추가 유료 시행은 이 코호트12회에서 종료했다.

[기계 판독 요약](summary.json) · [개별 비교](case-comparison.csv) · [선택12/NOT_RUN24 일정](selected-schedule.json) · [사전 프로토콜](../../../experiments/metacontroller/native-public-apps-mcv2-cost-regression-protocol-v1.md)
