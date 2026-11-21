# 독립 검토: A 통합본 완료 provider 시간 비교

## 출처와 실행 전 자료 검토

**최종24회에서 Gemini 정책은9/12, JeV 정책은5/12 공동 성공이었다. JeV 실제 완료 요청3회는 모두 보류됐고, 실패 포함 평균도15.686초→20.447초로 늘었다. JeV 완료 옵션의 기본 도입은 NO_GO다.** 실행 전 자료 검토 뒤 종료된 원자료·직접 요청 결합·전체 분모·실제 노출 쌍을 독립적으로 재확인했다. 감사자가 생성·수정한 파일은 이 문서뿐이며 runtime·protocol·prepared·원자료를 수정하지 않았다.

[동결 기록](source-freeze.json)은2026-11-21 02:23:58.714265 UTC에 source commit `0ee53cf98b36ccd1d3e59640432e3f1c1beb0421`과 tree `badad9ec9113fec660957e75505cff4cf84ae90e`를 기록했다. 소스69개를 해당 Git blob과 감사 시점 파일 해시에 대조했고 모두 일치했다. 준비10개(environment·manifest·native-component·unit-verification·블록별manifest6개)도 동결 해시와 같았다. 별도 보관된 app/test APK 두 파일의 SHA-256도 동결 값과 같았다.

[전체 manifest](manifest.json)는 A/CURRENT·normal만 포함하며 기본24회와 조건부12회,36개 고유 episode ID를 고정한다. 실제 기본 배정은Gemini-r1→JeV-r1→JeV-r2→Gemini-r2, 블록당 같은 여섯 목표다. 정책별12회·목표별2회·세 앱 각각 두 목표이며, 순서는 프로토콜과 같다. 초기 탐색 A6회는 이 비교 분모에 합치지 않는다. r3의12회는 사전 조건이 충족될 때만 전부 확장하는 별도 조건부 배정이다.

[단위 검사 기록](unit-verification.json)은 Android 관련269개, Backend 최종 관련469개 오류0과 app 모듈 build/lint 통과를 기록한다. 전체 루트 lint는 변경 범위 밖 design-preview의 기존 min26/API29 setMinHeight/Width 문제로 실패했으므로 전체 lint 통과라고 부르면 안 된다. 초기 sandbox socket bind 실패와 loopback 재실행, 다른 단계의 검사 수를 최종 실행 소스의 전수 검사 수로 합산하지 않는다.

[최종 모델 없는 native 구성요소 검사](native-component.json)는9개 모두 통과했다. 실제 목적 화면 여섯 양성 사례는 scripted 요청1회·native 조작0·pending 계획 보존·서로 다른 fresh native 프레임과415~476ms 간격을 기록했다. drawer 메뉴·오래된 화면 복사·실제 보호 입력 세 음성 사례의 false verified는0이다. 이는 이미 목적 상태에 도달한 경우의 구성요소 검사이고 실제 provider 정확도나 탐색 성공률 자료와 합치지 않는다.

첫 facade 실행은 [실행 오류 기록](blocks/gemini-r1/pre-call-launch-error.log)의 `ModuleNotFoundError: ai_agent`로 import 단계에서 중단됐다. 그 시점에는 listener나 모델 호출을 시작하지 못했고, repo runtime 코드를 바꾸지 않은 채 실행 helper의 AI-Agent import 경로를 보완했다. 그 뒤 준비 health는 공급자 연결을 확인하지 않는다는 표기를 유지한다. 이 보완은 동결 뒤 첫 유료 호출 전 환경 실행 수정이며 숨기지 않는다. 전체 요청 원장과 종료 자료를 추가 검토할 때 최초 시도 시각·미결합/누락 여부도 함께 확인한다.

## 해석 기준과 제한

실제24회에 대해 준비·공급자 실패·보류를 모두 유지하고, 모델 요청 시도·provider 미호출·unknown과 현재 완료 증거를 구분한다. 총 frontend API 요청을 실제 JeV 호출 수로 대체하지 않는다. JeV 정책에서도 정적 eligibility 밖의 목표는 호출 전에 Gemini를 사용하며, Clock처럼 로컬 조건으로 끝난 경우 완료 모델 요청 자체가 없을 수 있다. 이런 배정을 전체 분모에서 빼거나 JeV 지연0으로 세면 안 된다.

양쪽 실제 완료 provider에 노출된 동일 목표/반복 쌍4개 기준은 actual observed `result_only` 시도로 평가한다. 같은 JeV 요청 후의 보류·오류·timeout도 관측 시도이며 결과와 지연에 남는다. 노출은 성공을 뜻하지 않는다. 조건부 확장은 전체24회 종료·안전/계측·전체/앱별 품질 비회귀와 노출 부족 조건을 모두 만족해야 한다.

지연은 전체 배정 실패 포함 시간, provider 경계 시간, 현재 목적 도달 이후 추가 시간, 로컬 native 두 프레임을 구분한다. `model_wait_ms`는 순수 모델 연산 시간이 아니며 worker 시작·연결·공급자·응답 처리 경계를 포함한다. client work와 model_wait 차이도 CPU 프로파일이 아니다. 초기 A6회와 실행 시점이 달라 공급자 응답이 느려졌다는 관측만으로 신규 코드가 느려졌다고 단정하지 않는다. 현재 비교의G→J→J→G도 무작위화가 아니며 시간·네트워크 워밍업·공급자 변동은 남는다.

완료 provider만 바꾼 좁은 비교에서 성공·안전·전체 시간·총 시도를 함께 평가한다. 향후 관측 결과가 좋아도 모든 앱·기기·중장년층 사용성·순수 모델 속도의 우월을 입증하지 않는다. B는 재도입하지 않는다.

## 종료 원자료 독립 대조

전체24행의 episode ID·task/package·A/normal·팔 순서0이 사전 배정과 순서까지 정확히 일치하며 중복 ID는 없다. 12개 목표/반복 쌍의 시작 의미 지문이 양쪽에서 같았다. 종료 후 소스69개의 현재 파일·동결 commit Git blob·source-freeze 해시를 다시 대조했고 차이가 없었다. 준비10개도 동결 해시와 같고, [종료 기록](cohort-closure.json)은2026-11-21 02:34:28.454131 UTC에 소스·준비 불변과 전용 loopback 종료를 기록했다.

44개 고유 요청에 대해 embedded client trace→audit association→원래 server terminal의 request/task 참조를 대조했다. 모델 상태·호출 수·대기 시간, provider·planning mode·goal assessment·토큰 값의 존재/누락과 값에 불일치가 없었다. 직접 결합44/44, 호출 unknown·미결합·orphan은0이다. 실패 시도도 그대로 포함했다. 관측 false verified·native guard 위반·예기치 않은 설정 변경은0이다.

| 주 지표 | Gemini 완료 정책 | JeV 완료 정책 |
| --- | ---: | ---: |
| 배정·실행 | 12 | 12 |
| 공동 성공 | 9 | 5 |
| 실패 포함 평균 | 15,686.333ms | 20,446.583ms |
| 실제 provider 시도 | 22 | 22 |
| 공급자 오류 | 2 | 2 |
| plan 오류 | 0 | 1 |

| 앱 | Gemini 공동 성공 | JeV 공동 성공 |
| --- | ---: | ---: |
| com.android.settings | 3/4 | 2/4 |
| com.google.android.deskclock | 4/4 | 3/4 |
| com.google.android.documentsui | 2/4 | 0/4 |

JeV 정책의 성공은 전체와 세 앱 모두에서 적었고 실패 포함 평균은4,760.250ms 길었다. 탐색 planner는 양쪽 모두 Gemini여서 네 성공 차이를 모두 JeV의 인과 효과로 해석할 수는 없다. 반대로 완료 provider가 실제로 호출되지 않은 실패를 버려 JeV 성공률을 높여서는 안 된다.

## 실제 provider 지연과 노출

44회는 Gemini 탐색35회·Gemini 완료6회·JeV 완료3회다. JeV의 실제3회 대기는193·240·289ms(중앙값240ms)였고, 모두 `jev_completion_abstained`/`needs_input`으로 완료 후보를 제공하지 않았다. Files Recent 두 번과 Documents 한 번이며 세 시행 모두 독립 목적 상태에 도달했지만 공동 성공은 아니었다. 빠른 보류를 빠른 성공으로 계산하지 않은 판정이 적절하다.

Gemini 완료6회의 모델 경계 대기는 중앙값4,290ms·범위3,172~6,564ms였고, 탐색35회는 중앙값4,186ms·범위2,962~8,631ms였다. provider 오류도 이 지연과 실제 호출 수에 남아 있다. frontend 첫 API24회의 중앙값4,228ms는 사용자 첫 안내 전체 시간이 아니다. 요청 큐44개의 중앙값은0ms, work−model_wait 비모델 잔여시간 중앙값은7ms였으나 이를 순수 CPU나 순수 네트워크 시간으로 부르지는 않는다.

실제로 양쪽 완료 provider에 모두 노출된 동일 목표/반복 쌍은2개다. 이는4개 기준에 못 미치지만, 전체/앱별 품질 회귀가 있어 노출 수만 채우려고 조건부12회를 실행할 수 없다. [확장 판정](extension-decision.json)은 `DO_NOT_EXTEND`이며 조건부12회는 모두 NOT_RUN이다. 실패의 선택 재실행은 없고, 초기 탐색6회도 새24회에 합치지 않았다.

## 최종 판정

[README](README.md)와 [기계 판독 요약](summary.json)의 수치·NO_GO 결론에 모순을 발견하지 못했다. 계측·안전 gate는 통과했지만 JeV 정책은 성공 비회귀·실패 포함 시간·실제 노출 기준을 충족하지 못했다. A/CURRENT와 Gemini 기본 완료 경로를 유지하고 B를 재활성화하지 않는 결론이 맞다. 단일 기기·6개 목표·정책별 두 반복·비무작위 고정 과제 순서·공급자 변동이 남으므로 통계적 우월·범용성·중장년층 사용성이나 순수 모델 연산 속도를 입증한 실험으로 확대하지 않는다.

## 종료 후 별도 소스 검토

24회는 source `0ee53cf`로 종료했고 위69개 현재 파일 해시 대조도 후속 기억 수정 적용 전에 수행했다. 종료 후 약02:38 UTC에 적용한 `AppTaskExperienceService._ground`의 기억 재사용 중복 후보 수정은 이24회 실험에 포함되지 않는다. 이 후속 변경을 이번 시간·성공 결과의 원인이나 검증된 실제 지연 개선으로 소급하지 않는다.

후속 diff의 읽기 전용 안전 검토에서는 원래 기대값 묶음마다 두 개의 독립 확인 task와 adverse 이후 observed/received 순서 조건을 먼저 통과한 뒤에만, 같은 emitted action을 합치는 것을 확인했다. 신원에는 새 UUID와 진전 플래그를 제외한 모든 TaskAction 필드와 원래 after_key가 포함된다. 따라서 실제 목적 key·package·kind·target·bounds 차이는 경쟁 후보로 남는다. 진전 플래그는 보수적 AND로 합쳐 새 권한을 부여하지 않는다. 이 판단은 코드 검토이며 후속 전체 검사 수와 실기기 성능은 별도 결과를 따라야 한다.
