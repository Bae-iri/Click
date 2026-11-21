# 실제 앱 3종 A/B 결과 — MCv1

**B는 공동 성공이 2회 많았지만 API 요청 시도가 62.1% 늘었고, 무반응 복구는 양팔 모두 0회였다. B의 기본 활성화는 NO_GO다.** 정상 조건에서 관찰된 성공 차이는 보존하되, 이를 일반적인 복구 효과나 범용성 입증으로 해석하지 않는다.

여기서 실행명 `public-apps-b2`의 B는 **META / `mc_v1`**이다. A는 CURRENT다. [동결 소스](source-freeze.json)는 `63ecb80a3ae22582bf37570df480ec95035b1fd2`이며 이후 개발하는 `mc_v2` 결과와 구분한다. 실제 Settings·Clock·Files에서 두 목표씩 정상/수락 후 무반응/5.5초 지연 × A/B, 총 **36회·18쌍**을 실행했다. 단일 accountless 에뮬레이터의 scripted approval 실험이며 참여자는 없다.

## 무엇을 성공으로 셌나

독립 oracle이 목적 상태를 700ms 안정적으로 관찰하고, runtime이 두 fresh native frame으로 현재 결과를 verified로 제시하며, 예산 30초와 조작 후 750ms 대기·미처리 native action 없음·false verified 0 조건을 함께 만족해야 공동 성공이다. 모델의 자기 선언이나 메뉴에 목적 라벨이 보이는 것만으로 완료 처리하지 않는다. 사람의 결과 확인도 자동으로 입력하지 않았다.

## 조건별 비교

API 시도는 **Gemini 3.8 Flash에 보낸 관측 요청 시도**이며 공급자 실패도 포함한다. 실패 포함 시간은 성공 시 공동 시각, 실패 시 30초다.

| 조건 | A 공동 성공 | B 공동 성공 | A API 시도 | B API 시도 | A 실패 포함 평균 | B 실패 포함 평균 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 정상 | 3/6 | 5/6 | 11 | 14 | 18.739초 | 13.470초 |
| 수락 후 무반응 | 0/6 | 0/6 | 6 | 17 | 30.000초 | 30.000초 |
| 5.5초 지연 | 4/6 | 4/6 | 12 | 16 | 19.347초 | 20.347초 |
| 전체 | **7/18 (38.9%)** | **9/18 (50.0%)** | **29** | **47** | **22.695초** | **21.272초** |

B의 전체 평균은 약 1.423초 짧았고 API 시도는 18회 많았다. 지연 조건은 성공 수가 같으면서 B가 평균 1초 느리고 요청도 4회 많았다. 무반응에서는 요청이 6→17회로 늘었지만 어느 팔도 목표를 완료하지 못했다. 단순 목적 도달 A 9/18·B 12/18은 공동 성공과 별도 지표다.

## 앱별 비교

| 앱과 목표 | A 공동 성공 | B 공동 성공 | A API 시도 | B API 시도 |
| --- | ---: | ---: | ---: | ---: |
| Settings: Display, Language & region | 2/6 | 2/6 | 9 | 20 |
| Clock: Timer, Stopwatch | 4/6 | 4/6 | 6 | 8 |
| Files: Recent, Documents | 1/6 | 3/6 | 14 | 19 |

짝 결과는 양팔 성공 7쌍·B만 성공 2쌍·A만 성공 0쌍·양팔 실패 9쌍이다. B만 성공한 두 쌍은 정상 Files Recent/Documents였고, 두 A 시행의 마지막 요청은 provider deadline 실패였다. 공급자 변동이 섞여 있어 이 차이를 B 정책만의 인과 효과라고 할 수 없다. **양팔이 실제 장애에 노출되고 B만 성공한 쌍은 0개**다.

## 계측과 안전

정상 12회는 전체 36회의 원문 prefix로 보존됐다. 18쌍 모두 시작 의미 지문과 측정 context가 일치했다. [요청 감사](request-audit.json)는 client→terminal→episode의 **76개 요청을 모두 직접 결합**했으며 호출 수 unknown은 0이다. 공급자 deadline 실패 9건도 결과에서 제외하지 않았다. 최종 oracle unknown은 0이지만 중간 unknown poll은 총 5회 있었고 안정 확인을 끊었다.

각 장애 조건의 실제 노출은 A 5/6·B 6/6이다. A Language는 공급자 실패로 첫 TAP까지 가지 못했다. 미노출도 배정 분모에 남겼다. 지연 11건의 native dispatch는 실제 수락됐고 예약부터 적용까지 5,515~5,570ms였다. 관측 false verified·native guard 위반·예기치 않은 설정값 변경은 0건이다. 이는 [별도 보호 test APK 검사](guard-readiness.json)를 포함한 특정 환경의 관측 결과이며 모든 앱의 안전 보장은 아니다.

토큰은 전체를 알 수 없다. `total_tokens` 관측 소계는 A 38,682(4회 미확인), B 67,258(5회 미확인)이다. thinking_tokens는 전체 76회 중 12회만 값이 있어 미확인을 0으로 계산하지 않았다. 요청 시도 수를 청구 금액으로 환산하지 않는다.

원본 merged client/completion journal은 첫 줄에 없는 `previous.jsonl`의 `cat` 진단이 있어 엄밀한 JSONL이 아니다. 원문을 보존한 채 유효 JSON 76개/481개를 구분해 감사했다. embedded receipt의 직접 결합 및 주 성능 수치에는 차이가 없었다. 같은 보관 형식 문제가 이전 Settings v2에도 있음을 읽기만 해 확인했으며 이전 자료는 수정하지 않았다. 세부 해시·fresh frame·복구 context·형식 검토는 [독립 검토](independent-review.md)에 있다.

## 개선 방향과 판정

현재 가장 구체적인 비용 문제는 **실패가 미해결인데도 후속 일반 계획 요청이 복구 예약에 포함되지 않는 경로**다. 무반응 B Display는 요청 4회에 실패 예약 1회, Files Recent는 요청 3회에 예약 1회였다. 뒤따른 scroll/open_app 이후 요청이 일반 요청으로 기록됐다. 그렇다고 모든 초과 요청이 한 코드 경로 때문에 발생했다고 단정하거나 전부 제거 가능하다고 추정하지 않는다.

후속 [MCv2 비용 회귀 프로토콜](../../../experiments/metacontroller/native-public-apps-mcv2-cost-regression-protocol-v1.md)은 같은 세 앱·여섯 목표의 무반응 12회만 새 디렉터리에서 평가한다. 일반 요청까지 미해결 실패 예산에 넣고 native 검증 없는 계획 변경이 예산을 재설정하지 못하게 하는 변경을 검사한다. 성공·안전 기준이나 조작 권한을 완화하지 않으며, 비용 감소만으로 B GO를 선언하지 않는다. 후속 결과는 이 MCv1 결과와 별도다.

**일반 사용 도입은 NO_GO이며, 실패 예산과 복구 성과를 더 검증해야 한다.** 세 앱은 단일 Settings보다 넓은 범위지만 목표당 한 번으로 통계적 우월·미관측 앱 전체의 범용성·중장년층 사용성을 입증하지 않는다. 이전 Settings 결과와의 변화 역시 공통 완료 코드·B 정책·앱·공급자 응답이 함께 바뀐 기술 비교다.

[사전 프로토콜](../../../experiments/metacontroller/native-public-apps-protocol-v1.md) · [구현과 검사 범위](implementation.md) · [준비 과정](preparation-notes.md) · [재현 방법](reproduce.md) · [기계 판독 요약](summary.json) · [종료 기록](cohort-closure.json)
