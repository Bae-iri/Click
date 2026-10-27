# AI-Agent 평가와 상황별 대응

평가 기준: `develop`의 `47739ed`와 이번 작업의 변경사항. 2026-10-11.

처음 확인한 `feature/ai-agent`는 2026-08-29의 과거 브랜치였다. 당시의 `click_agent` / `ai_agent` 이중 구조와 결함을 현재 `develop`의 결함으로 일반화하면 안 된다. `develop`에서는 `click_agent`가 `ai_agent/runtime`으로 통합되어 있다. 이 문서는 현재 기본 API가 사용하는 `RuntimeAgentAdapter → ai_agent.runtime.ClickAgentEngine`을 기준으로 한다.

## 평가

AI-Agent(1)은 단순 골격을 넘어 행동 귀속, 결과 검증, 재관측, 대체 경로, Backend graph 재선택을 갖춘 구현이다. 그러나 실제 기기에서의 성공률을 입증한 제품으로 평가하기에는 자료가 부족하다. AI-Agent(2)의 graph 생성·검증·모델 학습·승격 모듈은 여전히 골격이다.

장점은 성공과 대기를 구분하고, 불확실할 때 조작 대상을 임의로 만들지 않으며, 실제 설정 변화와 사용자 만족을 별도로 기록한다는 점이다. graph 버전 고정, checkpoint 복원, 요청 중복 방지, 승인된 대상에만 적용되는 확인 절차도 구현되어 있다.

부족한 점은 실기기/OEM별 검증, 제한적인 runtime 목표 종류, 남은 legacy 실행 경로, 평가 데이터의 독립성, 실제 실행으로부터 개선안을 만드는 AI2 기능이다. UI 점수는 규칙 점수이며 성공 확률이 아니다.

## 이번에 수정한 범위

### 실패 횟수

관측 횟수, 대기 시간, 사용자의 승인 여부를 실제 행동 실패 횟수로 쓰지 않는다. 기존 행동 귀속 로직에 더해 집계 시점에서도 다음 조건을 확인한다.

- `NO_CHANGE` 또는 `WRONG_STATE`이며 검증기가 집계 가능한 결과로 판단했음.
- 결과의 attempt ID가 현재 시도와 일치함.
- transition과 target key가 현재 시도에 연결됨.
- 귀속된 행동 event와 안내 관측 이후의 행동 시각이 있음.

대상별 실패 횟수와 전체 실패 예산은 같은 판정 함수를 사용한다. 실제 시도 실패가 확인된 경우에만 기존 정책에 따라 같은 대상 2회 후 제외, graph 내 실패 6회 후 Backend 재선택을 요청한다. 관측 복구 제한은 별도이며 사용자 행동 실패로 집계하지 않는다.

### 완료 판정

현재 글자 크기 작업의 완료 조건은 다음 조건의 AND다.

1. 현재 안내·목표 대상·작업 episode에 연결된 실제 행동.
2. 원래 기준 관측보다 뒤에 발생한 행동과, 행동 이후의 새로운 관측.
3. 원래 대상과 같은 의미 식별자·window이며 유일하고 조작 가능한 컨트롤.
4. 유한하고 관측된 범위 안에 있는 slider 값, 비교 가능한 조작 전후 범위.
5. 목표 slider 값이 요청 방향으로 변화.
6. Android `Settings.System.FONT_SCALE`이 같은 방향으로 실제 변화.
7. 해당 graph 전이가 작업 완료를 선언하는 전이임.

밝기 증가, 승인만 한 상태, 화면 제목, 진행률 100%, OCR의 문자, 사용자의 만족 응답만으로 기술적 성공을 만들지 않는다.

다음 추가 결함을 현재 develop에서 실행 재현한 뒤 수정했다.

| 재현 | 수정 전 | 수정 후 |
| --- | --- | --- |
| 안내 이후 행동 전에 FONT_SCALE만 먼저 증가하고, 나중에 slider 변화가 전달됨 | 기존 기준값으로 DONE | 기존 안내를 재사용하지 않고 다시 확인 |
| slider와 FONT_SCALE이 이미 증가한 관측 뒤의 행동 event가 늦게 도착 | 과거 변화를 뒤의 행동에 귀속해 DONE | 행동 시각과 이미 받은 관측을 비교해 이전 안내 무효화; 같은 event 재전송도 성공 불가 |
| slider 범위가 0~5인데 현재값 99, FONT_SCALE도 증가 | DONE | UNKNOWN, 재관측/사용자 도움 |
| 조작 전후 slider 범위가 달라짐 | 숫자만 비교해 DONE 가능 | UNKNOWN, 다른 척도의 값 비교 금지 |

반대로 실제 행동이 먼저 있었고 그 이후 변화가 관측되었지만 event 전송만 늦은 경우는 정상 완료로 처리한다. 도착 순서와 실제 발생 순서를 구분한다.

근거: [행동·완료 검증](../../AI-Agent/src/ai_agent/runtime/verification.py), [실패 집계](../../AI-Agent/src/ai_agent/runtime/state_manager.py), [런타임](../../AI-Agent/src/ai_agent/runtime/engine.py), [회귀 테스트](../../AI-Agent/tests/test_runtime_reliability.py), [HTTP 및 checkpoint 테스트](../../Backend/tests/test_runtime_evidence.py), [결과 확인 테스트](../../Backend/tests/test_task_outcomes.py).

## 상황별 현재 대응과 필요한 대응

아래 표는 실행 단계별 주요 실패 유형과 복합 상황을 정리한 것이다. 임의의 기기·외부 앱·이벤트 순서에 대한 모든 조합을 실기기에서 검증했다는 의미는 아니다. '유지'는 현재 정책이 타당하다는 평가이며, '추가'는 미구현 또는 검증 보강이 필요한 항목이다.

### 목표와 사용자 상태

| 상황 | 현재 대응 | 필요한 대응/남은 점 |
| --- | --- | --- |
| 목표가 모호하거나 방향이 불명확 | Backend 목표 준비 단계의 질문과 runtime clarification | 유지. 부정문·복합 요청·오인식 발화에 대한 독립 평가 추가 |
| 지원하지 않는 목표 | 지원 graph/typed goal 계약에서 거절 | 지원 작업을 구체적으로 제시. catalog에 있다는 사실과 runtime 지원을 구분 |
| 글자 확대와 축소 | typed runtime은 두 목표와 방향별 predicate 지원 | 다른 설정으로 확장할 때 실제 설정값 검증기를 작업별로 추가 |
| 사용자가 가만히 기다림 | WAITING_FOR_ACTION, 실패 예산 유지 | 유지. 사용자의 읽는 속도를 실패로 해석하지 않기 |
| 사용자가 직접 다음 화면에 도달 | 관측된 진행으로 재분류; 안내 실행의 성공 횟수로 세지 않음 | 유지. 수동 진척과 Agent 행동 성과를 평가에서도 구분 |
| 뒤로 이동·다른 앱 전환·잘못된 메뉴 | 현재 화면을 다시 분류하고 graph 안에서 선택/복구 | OEM별 돌아오기 경로와 잠금/전화/권한 팝업 실기기 검증 |
| 목표 교체·일시정지·취소 | Backend command, session version, episode로 제어 | 해당 API를 사용하고 engine에 새 raw goal만 넣어 기존 세션을 재활용하지 않기 |
| 사용자가 확인을 거절 | BLOCKED, 조작 안내 중단 | 유지. 취소 뒤 도착한 요청도 episode/version 검증 유지 |

### 화면 관측과 대상 선택

| 상황 | 현재 대응 | 필요한 대응/남은 점 |
| --- | --- | --- |
| Accessibility Tree가 비어 있음 | 재관측 또는 매 사용 동의가 필요한 Vision 요청 | 권한 꺼짐·화면 잠금·수집 지연의 원인을 FE에서 더 구체화 |
| 대상이 화면 아래에 있음 | 조건에 맞는 스크롤 목록이면 수동 탐색 안내 | 현재 runtime의 자동 scroll 미지원 범위를 명시하고 탐색 종료 UX 실기기 확인 |
| 같은 이름의 버튼이 둘 있음 | 점수 격차가 작으면 대상 선택 보류 | resource/구조 근거가 없으면 계속 추측하지 말고 사용자 선택 제공 |
| 비활성·숨김·영역 없는 컨트롤 | 후보에서 제외 | 유지. 가려짐과 실제 hit-test는 실기기 검증 추가 |
| 제목은 맞지만 실제 조작 대상은 없음 | 제목을 좌표로 임의 확대하지 않고 재관측 | 유지. '보이지만 누를 수 없음'을 사용자에게 구별해 설명 |
| 팝업·다른 window의 비슷한 항목 | active window 필터와 제목 충돌/모호성 처리 | window 정보 없는 입력, 다중 창, overlay 좌표 변환 검증 필요 |
| 화면 회전·글자 확대 후 배치 변화 | 새 bounds/target binding에 따라 안내 갱신 | 회전·배율·시스템 inset 변화 중 오래된 overlay 제거를 실기기 확인 |
| OEM/OS/언어별 메뉴가 다름 | graph selector, 동의어, 상태 규칙, 보조 similarity | 지원 환경별 실수집 데이터와 승인 graph 분기 확대 |
| 오래되거나 중복된 관측 | WAITING_FOR_OBSERVATION, 새 관측 요청; 실패 예산 유지 | 유지. 같은 ID로 다른 payload를 보내지 않는 FE 계약 검증 |

### 행동과 성공·실패 판정

| 상황 | 현재 대응 | 필요한 대응/남은 점 |
| --- | --- | --- |
| 안내와 다른 버튼을 누름 | 잘못 귀속된 event는 무시; 실제 새 화면으로 재분류 | 유지. 다른 행동을 원래 대상의 실패로 세지 않기 |
| 다른 guide/session/episode의 event | 귀속 거절 | 유지. Backend 승인과 FE 이벤트 수집을 함께 검증 |
| 행동 event가 여러 번 전송됨 | 처리한 event key와 guide binding으로 중복 억제 | 유지. checkpoint 복원 후 중복도 회귀 검증 |
| 행동 후 화면이 아직 움직임 | 안정화 관측 대기 | 유지. 기본 500ms는 정책값으로 기기별 지연 분포 확인 |
| 실제 행동 후 안정된 화면에서도 전이 실패 | 두 개의 새 안정된 관측 후 실제 실패 1회 | 유지. timeout/관측 불가를 같은 실패로 세지 않기 |
| 행동 없이 값이 바뀜 | 완료하지 않음; 재확인 또는 행동 근거 대기 | 이번 보강으로 뒤의 event가 앞의 변화를 성공으로 가져가지 못하게 함 |
| event가 늦게 도착 | 실제 발생 시각과 관측 시각으로 연결 | 앞선 실제 행동은 허용, 이미 바뀐 후 행동은 거절; 양쪽 회귀 테스트 |
| 밝기/음량/다른 slider만 증가 | 목표 selector·target key와 FONT_SCALE 조건으로 완료 불가 | 다른 slider와 설정 변화가 동시에 생기는 복합 테스트 유지 |
| 목표 slider만 움직이고 실제 설정은 그대로 | 완료 불가 | 유지. 적용 버튼이 필요한 기기는 별도 graph 전이 정의 |
| FONT_SCALE만 바뀌고 목표 slider는 그대로 | 완료 불가 | 유지. 외부 설정 변화와 Agent 기여를 혼동하지 않기 |
| slider/설정값·시각이 누락됨 | UNKNOWN 또는 행동/관측 대기 | 근거 없는 DONE 금지. 기기별 값 수집 지원 범위 명시 |
| 범위 밖 값·조작 전후 범위 변경 | 이번 보강으로 UNKNOWN | 재관측/새 기준으로 재시도. 정상 한도 도달과 구분 |
| 목표 방향과 반대로 값이 변함 | 완료 불가; 안정된 관측 이후 실패/복구 | 유지. 확대뿐 아니라 축소에도 같은 정책 적용 |
| 이미 최대·최소에 도달 | 추가 조작 중단, 한도 결과 안내 | 기술적 변경 성공과 한도 도달을 구분하고 반대 방향/종료 선택 제공 |
| 기술적으로 성공했지만 사용자가 불만족 | 결과 확인 후 추가 조정 또는 중단 | 유지. CHANGED와 사용자 만족, 최종 ACHIEVED를 별도 축으로 관리 |

### 복구·서버·외부 기능

| 상황 | 현재 대응 | 필요한 대응/남은 점 |
| --- | --- | --- |
| 같은 대상에서 실제 실패 반복 | 대상별 2회 후 제외, graph의 대체 transition 탐색 | 유지. node index 변경에도 의미 식별자 기준 제외 유지 |
| 관측 복구가 계속 실패 | 기본 새 관측 3회 또는 5초 이후 사용자 도움 대기 | 사용자 행동 실패 예산과 분리 유지; 바로 실패 종료하지 않기 |
| graph 내 경로 소진/실패 예산 소진 | Backend 재선택 요청 | 승인된 호환 graph만 선택; 무제한 순환 금지 |
| 대체 graph가 없거나 재선택 제한 초과 | 작업 목표를 유지하며 재확인·직접 이동·중단 제공 | 유지. Backend는 재선택 2회/누적 실패 12회 제한 사용 |
| 요청 재전송·응답 순서 뒤바뀜·동시 요청 | 요청 digest/cache, version, lease, episode 검증 | 유지. 실제 네트워크 장애와 모바일 재접속 시험 추가 |
| 서버 재시작/런타임 상태 복원 | DB checkpoint와 graph version/hash 복원 | 기존 checkpoint에는 새 범위 필드가 없어도 복원 가능; PostgreSQL 다중 worker 검증 별도 |
| graph 버전이나 checkpoint가 호환되지 않음 | 충돌 응답으로 실행 차단 | 사용자가 목표를 잃지 않도록 새 episode 재개 UX 연결 |
| 모델 호출 실패·잘못된 embedding·시간 초과 | 응답 검증과 keyword fallback, turn deadline | 외부 모델 실제 장애 주입 및 지연 측정; 작업별 어휘와 cache 수명 개선 |
| turn 처리 초과/동시 처리 포화 | Backend 504/503, 실행 상태는 격리 | 실행 thread를 강제 종료하지는 않으므로 장기 정지 작업의 슬롯 회수/worker 격리 보강 |
| Vision 동의 없음/만료/다른 요청의 동의 | 매 요청의 신뢰된 동의 검증 | 유지. 화면 읽기 동의와 설정 변경 승인을 분리 |
| Vision 마스킹 실패 | payload 제거 및 안내 차단 | 유지. 실패 시 원본 화면을 외부로 보내지 않기 |
| OCR 신뢰도 낮음/좌표 없음/분석 실패 | 후보 제외 또는 사용자 도움으로 전환 | 기종·언어·작은 글자별 OCR 실측 평가 필요 |
| Vision만으로 설정 완료 추정 | Vision node를 값 검증의 근거로 사용하지 않음 | 유지. 읽힌 문자열을 실제 시스템 설정값으로 대체하지 않기 |
| 실행 로그를 학습 정답으로 쓰려 함 | 기술 결과·사용자 보고·진단을 분리하는 계약 존재 | 독립 검수한 gold, 데이터 분할, 회귀 평가와 승인 release 구현 필요 |

근거 모듈: [복구](../../AI-Agent/src/ai_agent/runtime/recovery.py), [대상 선택](../../AI-Agent/src/ai_agent/runtime/targeting.py), [Vision](../../AI-Agent/src/ai_agent/runtime/vision.py), [Backend turn 조율](../../Backend/app/services/agent_turn.py), [graph 재선택](../../Backend/app/services/runtime_recovery.py), [실행 제한](../../Backend/app/clients/turn_executor.py).

## 후속 우선순위

1. **P1: 실기기 검증.** Android/OEM/언어별로 실제 행동, 관측, FONT_SCALE을 수집하고 오완료·오선택·잘못된 종료·복구 성공률을 별도 측정한다. 현재 합성 replay와 자동 테스트 통과는 실제 기기 성공률이 아니다.
2. **P1: legacy 실행 경로 정리.** 기본값 `enable_legacy=False`로 보호되지만 `Backend/app/services/guidance.py`의 선택적 옛 경로는 확인 후 다음 상태를 저장해 실제 변경 없이 완료할 수 있다. `Backend/app/clients/agent.py`에도 임의 slider 증가 판정 코드가 남아 있다. 다시 활성화하기 전에 제거하거나 현재 runtime/검증 계약에 연결해야 한다. 이번 수정 범위는 현재 기본 runtime이다.
3. **P1: 목표별 완료 계약 확대.** runtime typed validator는 글자 확대/축소를 허용하고 값 검증기는 FONT_SCALE에 연결되어 있다. 다른 설정은 해당 시스템 값·적용 단계·위험도·완료 predicate를 정의한 후 연결한다.
4. **P2: AI2 실제 기능 구현.** GraphLearner는 빈 proposal, TrainingDatasetBuilder는 빈 samples, GraphValidator는 NEEDS_DATA를 반환한다. ModelTrainer/GraphVersionManager도 실제 학습·배포를 하지 않는다. 독립 gold → 실패 재구성 → 후보 → 회귀 평가 → 승인·롤백 순으로 구현한다.
5. **P2: 지연과 운영 관측.** per-turn scorer/cache 생성과 후보별 similarity 호출 비용을 측정한다. timeout 뒤 실행을 계속하는 worker의 자원 회수와 원인별 metrics를 강화한다.
6. **P2: 문서·평가 정합성.** README 일부의 'Backend 충돌로 통합 실행 차단' 설명은 현재 테스트 실행 결과와 맞지 않는다. 역사적 결과와 현재 결과를 분리하고 지원 작업·기종·확인 수준을 명시한다.

## 검증 범위

- 브랜치에 추적된 테스트 파일만 실행하여 기존 미추적 복사본 파일의 영향을 제외했다.
- AI-Agent 전체: **158 passed**.
- Backend 전체: **868 passed**, 기존 Pydantic 필드 속성 경고 1건.
- 기존 테스트에서 행동 시각/FONT_SCALE이 빠져 다른 조건 때문에 통과하던 일부 부정 사례를 수정했다. 이제 타깃/행동 조건 하나가 잘못된 경우에도 나머지 근거를 유효하게 공급해 검증한다.
- HTTP checkpoint 복원 후의 무조작 대기, 중복 event, 사전 설정 변화, 새 승인 뒤의 실제 성공을 확인했다.
- Android 실기기, 원격 모델 실호출, PostgreSQL 별도 smoke/load 시험은 이번 검증에 포함하지 않았다.
- 이번 코드는 외부 상태의 원인을 완전히 관측하는 인과 추론기가 아니다. 일치하는 클라이언트 행동 근거와 시스템 설정값의 시간적 일관성을 강제한다. 행동과 동시에 발생해 구분할 수 없는 외부 변화는 추가 기기 관측이 필요하다.
