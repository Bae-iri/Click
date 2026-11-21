# 독립 검토: 종료된 실제 앱 MCv1 코호트

**원자료에서 공동 성공 A 7/18·B 9/18, 실제 API 시도 A 29·B 47을 재계산했고 요약과 일치했다.** B의 성공 증가 2건은 정상 조건의 Files 두 목표에만 있었으며, 무반응 복구는 양팔 모두 0/6이었다. 더 많은 API 시도와 공급자 응답 변동을 감안하면 B의 기본 활성화 또는 일반적인 복구 우월성을 뒷받침하지 않는다.

이 검토는 모델·ADB·빌드 재실행 없이 [36개 episode](episodes.jsonl), [76개 terminal](provider-terminal.jsonl), [직접 결합 감사](request-audit.json), [요약](summary.json), 준비·완료 journal과 [종료 기록](cohort-closure.json)을 읽어 수행했다. 현재 작업 트리는 후속 MCv2 개발로 달라졌으므로 실행 소스 검증에는 **동결 commit `63ecb80a3ae22582bf37570df480ec95035b1fd2`의 Git blob**을 사용했다. 이 검토로 원자료·동결 메타데이터·기존 Settings v2 자료를 변경하지 않았다.

## 일정·출처·분모

- 전체 manifest의 36개 episode ID·task/package·조건·팔·팔 순서가 원자료 순서와 정확히 일치한다. 18쌍 모두 양팔의 초기 의미 지문·measurement context가 같고 setup이 유효했다.
- 정상 12개 원문 바이트가 전체 36개 원자료의 prefix로 그대로 남아 있다. 정상 사례를 교체하거나 다시 고른 증거는 없다.
- 동결된 소스 40개는 Git blob SHA256, source-freeze 값, 종료 시점에 수집한 SHA256이 모두 일치한다. 준비 자료 5개도 동결 해시와 일치한다. 현재 MCv2 소스 해시를 MCv1에 대입하지 않았다.
- [동결 기록](source-freeze.json)은 `2026-11-20T14:34:16.973326Z`에 facade 미시작·port 닫힘·관측 모델 시도 0을 기록한다. 첫 client 요청의 guest 시각 추정 시작은 `14:34:53.290Z`로, 기록된 guest/host 차이를 고려해도 동결보다 약 40초 뒤다. 이 추정에 맞춰 원본 native 시각을 고쳐 쓰지는 않았다.
- 종료 기록은 `14:57:16.014751Z`, 36개 episode·76개 terminal·provider drain idle이다. 준비와 보호 fixture는 성능 분모나 참여자로 합산하지 않는다.

## 결과 재계산

공동 성공은 독립 목적 안정·현재 runtime verified·예산 내 공동 시각·false verified 0·미처리 native 조작 없음으로 다시 검사했다. 실패 포함 시간은 성공 시 공동 시각, 나머지는 30초로 계산했다.

| 조건 | A 공동 성공 | B 공동 성공 | A API 시도 | B API 시도 | A 실패 포함 평균 | B 실패 포함 평균 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 정상 | 3/6 | 5/6 | 11 | 14 | 18.739초 | 13.470초 |
| 수락 후 무반응 | 0/6 | 0/6 | 6 | 17 | 30.000초 | 30.000초 |
| 5.5초 지연 | 4/6 | 4/6 | 12 | 16 | 19.347초 | 20.347초 |
| 전체 | 7/18 | 9/18 | 29 | 47 | 22.695초 | 21.272초 |

짝 결과는 양팔 성공 7쌍, B만 성공 2쌍, A만 성공 0쌍, 양팔 실패 9쌍이다. B는 전체 평균이 약 1.423초 짧았지만 API 시도는 18회, **62.1%** 많았다. 단순 목적 도달은 A 9/18·B 12/18로, 공동 완료와 구분해야 한다.

| 앱 | A 공동 성공 / API | B 공동 성공 / API |
| --- | ---: | ---: |
| Settings | 2/6 / 9 | 2/6 / 20 |
| Clock | 4/6 / 6 | 4/6 / 8 |
| Files | 1/6 / 14 | 3/6 / 19 |

B만 성공한 두 쌍은 정상 `files_recent`, `files_documents`다. 두 A 시행은 마지막 요청에서 provider deadline 실패를 겪었다. 따라서 이 차이를 MC 정책의 인과 효과로 배분할 수 없다. 양팔 모두 실제 장애에 노출되고 B만 공동 성공한 쌍은 0개다.

## 요청·토큰·context의 직접 결합

76개 고유 request가 embedded client receipt, 실제 client journal의 유효 JSON, server terminal, audit association에 빠짐없이 대응한다. task/request/server 참조, 호출 상태·수·대기, 토큰의 값과 필드 유무, 닫힌 context 필드가 일치한다. journal의 `observed_model_calls`를 harness의 `model_calls`로 바꾸는 명시적 정규화 외에 내용 차이는 없었다.

모든 시도는 `gemini-3.8-flash`, observed 1회, provider attempt limit 1이다. 공급자 deadline 실패 9건(A 4·B 5)도 비용·실패 분모에 남아 있다. 호출 수 unknown·미결합·고아 request는 0건이고 post-model navigation 계약 거절은 0건이다. HTTP 200은 이 9개 공급자 실패까지 성공으로 만든다는 뜻이 아니다.

실제 provider 시도에 `recovery_context_used=true`인 요청은 B의 8개다. 정책 `mc_v1`·이유·attempt와 capability/allowed-action 메타데이터가 terminal→client→association에 그대로 복사됐다. 정상 1개와 지연 1개는 `observed_transition_unconfirmed`, 무반응 6개는 `accepted_action_unconfirmed`였다. 이는 기술적 전달 증거이며 모델이 context를 이해하거나 성공에 사용했다는 증거는 아니다.

| 토큰 필드 | 관측 소계 | 값 있는 시도 | 값 미확인 시도 |
| --- | ---: | ---: | ---: |
| input_tokens | 95,619 | 67 | 9 |
| output_tokens | 7,303 | 67 | 9 |
| thinking_tokens | 3,018 | 12 | 64 |
| total_tokens | 105,940 | 67 | 9 |

팔별 total_tokens 관측 소계는 A 38,682(미확인 4회), B 67,258(미확인 5회)다. 미확인을 0으로 바꾸지 않았으며, 완전한 총 토큰·thinking 사용량이나 청구 금액 비교로 확대하면 안 된다.

## native 완료·장애·안전 증거

완료 journal의 유효 event는 481개다. `verified` event 16개는 공동 성공 16개와 task별로 정확히 대응하며, 모두 같은 candidate/version/window에서 다른 observation 참조의 첫·둘째 fresh native frame을 갖는다. 두 프레임 검증 대기는 351~756ms이고 frame_count=2다. 동결 Controller가 요구하는 350ms 조건과 일치한다. 이는 runtime의 검증 제시이며 사람의 완료 확인을 흉내 낸 결과가 아니다.

비공동 성공 시행이 30초 전에 관찰을 멈춘 사례나 예산 뒤 poll/verified 시각을 예산 내 결과로 센 사례는 없었다. 최종 oracle unknown은 0이지만 **중간 unknown poll은 4개 B 시행에서 총 5회**였다. 동결 코드대로 안정 확인을 끊으며 알려진 음성으로 바꾸지 않았다. 정리 후 목적 존재는 최초 도달이나 공동 성공으로 사용하지 않았다.

무반응과 지연 조건 각각 A는 5/6, B는 6/6 실제 장애에 노출됐다. 두 조건의 A Language 시행은 공급자 실패로 첫 TAP까지 가지 못했다. 이 미노출 시행도 배정 분모에서 빼지 않았다. 각 조건에서 양팔 노출 쌍은 5개다. 지연 11개는 실제 native dispatch가 수락됐고, 예약부터 적용까지 5,515~5,570ms였다. 이 대기 구간에 다른 native action trace가 끼어든 사례는 없었다.

관측 false verified·native guard 위반·예기치 않은 설정값 변경·privacy guard 거절은 모두 0이었다. 정리 종료 시 미처리 native action과 gateway callback도 0이었다. [native guard](guard-readiness.json)는 실제 세 앱의 같은 화면 조작 수락·변경 화면 재사용 거절과 별도 test APK의 빈 native 비밀번호 입력/카운터 불변, 보호된 같은 프레임의 policy/native 거절을 기록한다. 이는 계정 없는 특정 환경에서 관측한 범위이며 실제 금융 앱·개인 파일·모든 앱의 안전을 입증하는 검사는 아니다. 전체 화면 원본은 저장하지 않아 과거 모든 프레임의 UI를 사후 독립 재판정할 수는 없다.

## 비용 원인: 확인한 사실과 한계

동결 `AppTaskMetaController`는 새 subgoal ID에서 scoped route 집합을 지우고, native verifier가 VERIFIED이면 recovery context와 scoped route를 지웠다. `claimPlannerRequest`는 `accepted_action_unconfirmed` recovery context가 활성일 때만 실패 요청을 예약하고, context가 없으면 일반 요청을 통과시켰다. 따라서 목표가 아직 미해결이어도 action-only 확인·계획 ID 변경 뒤 일반 계획 요청이 해당 실패 예약에 포함되지 않는 코드 경로가 존재한다.

실제 무반응 B에서 Display는 4회 요청에 실패 예약 1회, Files Recent는 3회 요청에 예약 1회였다. Display는 복구 context 요청 뒤 scroll forward/back와 일반 요청 두 번, Files Recent는 open_app 뒤 일반 요청 한 번을 기록했다. Language의 4회 중에는 장애 전의 정당한 탐색 요청과 복구 뒤 일반 요청이 함께 있다. 무반응 B 전체는 API 17회·복구 context/실패 예약 6회다.

이 기록은 일반 요청 비용이 실패 예약 수로 전부 설명되지 않음을 보여준다. 그러나 이전 자료에는 매 요청의 실패 epoch·route 예약·초기화 시점 전체가 없으므로, 11회 초과분 모두를 한 reset 원인으로 단정하거나 수정 뒤 정확히 몇 회가 사라질지 예측할 수 없다. 후속 MCv2 검사는 별도 소스·프로토콜·자료로 평가해야 한다.

## 보관 형식 한계와 판정

원본 `client-receipts.jsonl`과 `completion-events.jsonl`은 각각 첫 줄에 존재하지 않는 `previous.jsonl`의 `cat` 진단이 있어 엄밀한 JSONL이 아니다. 진단을 제외한 유효 기록은 각각 76개·481개이며 embedded trace 및 결과와 일치한다. 선택 journal 부분이 없을 때 ADB가 진단을 stdout으로 주고 성공 종료한 수집 문제다. 원본을 고쳐 쓰지 않고 정제본·제거 행·원본 해시를 별도 보관해야 한다.

이전 Settings v2도 읽기만 해 확인했다. 해당 merged/previous 원본에 같은 진단이 있고, 유효 client receipt 108개·completion event 455개가 있다. 이 사실은 이 새 문서에만 기록하며 이전 보고서·원본·해시를 수정하지 않았다. 이 형식 문제를 실제 시행 실패나 추가 모델 호출로 세지는 않는다.

**판정은 NO_GO를 유지한다.** 작은 기술 표본에서 관측된 정상 성공 증가를 부정하지 않지만, 장애 복구 이득은 확인되지 않았고 비용은 증가했다. 단일 에뮬레이터·세 앱·목표별 한 번·scripted approval·참여자 0명이며, 통계적 우월·모든 앱의 범용성·중장년층 사용성을 주장할 수 없다.
