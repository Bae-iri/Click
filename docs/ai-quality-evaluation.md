# AI 실제 품질 평가·정답 데이터 기준 v2

기준일: 2026-10-10. 적용 대상은 한국어 STT, FE 목표/명령 해석, Backend graph 선택, AI1 화면/target/검증/복구, AI2 라벨·graph 후보다. **기준을 제정한 것과 실제 gold 수집·제품 성능 검증을 완료한 것은 구분한다.** 현재 실제 발화 benchmark와 실기기 gold 평가는 이 작업에서 수행하지 않았다.

이 문서는 [데이터 개발 계획](./task-graph-data-plan.md)의 평가 단위·검수·split·지표·인수 기준을 구체화한다. 평가 도구의 자동 집행은 후속 구현이며, 문서만으로 배포가 승인되지는 않는다. [초기 manifest](../AI-Agent/data/evaluation/manifest-template.json)는 표본이 없는 템플릿이다.

**v2 재검증 결론:** v1의 CER 10%, 의미 일치 95%, coverage 90%, 600발화는 외부 공인 합격선이 아니었다. 이 수치로 자동 PASS를 부여하는 정책은 보류한다. 공개 benchmark의 원래 정답·split·채점법, 실제 제품의 독립 관측, 위험에 근거해 사전 확정할 인수 기준을 구분한다. 공개 데이터도 모든 제품 환경을 대표하거나 모든 라벨이 정확하다는 뜻은 아니다. 상세 근거와 재현 결과는 §9~§11에 있다.

## 1. 평가 단위와 책임

| 단계 | 정답의 단위 | 정답 근거 | 주 지표 / 소유자 |
| --- | --- | --- | --- |
| STT | 한 화자의 한 발화 | 동의받은 원음 + 독립 전사 | CER, 핵심 의미 보존, 무음 환각 / FE·음성 담당 |
| Goal·Command | 확정/모호한 요청 한 건 | 사용자 의미, 대상·범위·방향·부정·필수 slot, 필요한 질문 | goal/slot/command 정확도, 잘못된 실행 개시 / FE |
| Graph 선택 | goal·환경·catalog snapshot 한 건 | 승인·활성·호환 조건, 유효 후보 집합, 선택 정책 | 허용 집합 내 선택, 거절·모호성 처리 / Backend |
| State·UI target | 한 시점의 관측과 현재 task | 실제 화면·AX·window·독립적으로 식별한 조작 control | state 정확도, 잘못된 target 안내, 보류·coverage / AI1 |
| 표시·행동 방향 | guide가 실제 화면에 표시되는 사건 | 표시 시각의 화면과 control, 좌표계, 목표 방향 | 오래된 안내, bounds 오차, 방향 오류 / FE·AI1 |
| 완료·복구 | attempt 및 episode | 행동 전후 대상 값·OS 설정·시각·독립 판정 | 완료 오판·누락, 근거 없는 성공, 복구·task 성공 / AI1 |
| AI2 라벨·graph 후보 | 라벨 revision / 후보와 base graph | 원천 evidence, 독립 검수, 후보 생성에 안 쓴 평가 자료 | 라벨 일치, 후보 개선·회귀 / AI2 + 독립 검수자 |

실제 FE typed 경로와 legacy raw-goal 경로를 분리 보고한다. 음성 입력부터 사용자 결과 확인까지 end-to-end 평가를 별도로 한다. STT에 정답 전사를 넣거나 graph 선택에 정답 goal을 넣는 단계별 평가는 그 조건을 명시한다.

## 2. 데이터 등급

| source_kind | 사용 가능 범위 | 제품 성능의 gold로 직접 사용 |
| --- | --- | --- |
| `synthetic_fixture` | 단위·회귀 테스트, 계약 경계 | 아니오 |
| `synthetic_counterexample` | 알려진 결함의 재현·개발 회귀 | 아니오 |
| `public_reference` | 해당 공개 benchmark의 평가 또는 설계 참고 | 우리 제품의 기기/사용자 성능으로 전용 불가 |
| `public_benchmark_gold` | 출처·revision·공식 정답·평가 프로토콜이 확인된 외부 benchmark | 해당 benchmark 범위의 정답. Click 제품 gold와 구분 |
| `derived_public_annotation` | 공개 자료를 우리 schema로 변환하거나 추가 검수한 라벨 | 원본 정답과 변환/추가 판단을 분리한 뒤 지정 범위만 |
| `runtime_evidence` | 재처리·검수 입력 | 검수 전에는 아니오 |
| `weak_label` | 검수 보조·학습 후보 | 아니오 |
| `product_gold` | 지정 환경·판정 단위의 독립 평가 | 아래 승격 조건을 만족한 범위만 |
| `graph_candidate` | 구조 검사·held-out 비교 대상 | 실행 가능 graph 또는 정답으로 자동 승격 금지 |

`SUCCESS`, `DONE`, `label_status=final`, DB `approved` 필드만으로 제품 gold라고 인정하지 않는다. 합성 테스트 성공률·공개 benchmark 점수·실제 제품 성공률을 합산하지 않는다.

## 3. Gold record 계약

모든 record는 아래 필드를 갖는 버전 있는 데이터로 관리한다. 해당 단계에 필요 없는 값은 `null`과 사유를 남기며 임의 값으로 채우지 않는다.

| 묶음 | 필수 정보 |
| --- | --- |
| 식별·출처 | `record_id`, `schema_version`, `source_kind`, `source_group_id`, 수집 방법·시각, source revision/hash |
| 사용 범위 | task/goal, 언어, 제조사·모델·OS·앱 버전·화면 크기·방향·글자/표시 크기, STT 녹음 조건 |
| 개인정보 | `consent_ref`, 허용 목적, 보존 기한, 마스킹 상태, 접근 제한 evidence 참조, 삭제/철회 상태 |
| 실행 연결 | session/episode/step/guide/attempt/action/observation의 가명 ID, graph ID/version/hash, model/code/config revision |
| 입력 근거 | 관측/음성 reference와 hash, 전후 값, event 시각·순서·시계 기준, window·target identity·bounds, 관측 누락 표시 |
| 정답 | task별 expected label, 허용 target/graph/경로 집합, `ABSTAIN` 또는 `UNKNOWN` 사유, 실제 outcome, 근거 충족 여부 |
| 검수 | 공개 정답은 배포자·annotation revision·공개 검수 방식과 우리의 audit 범위. 새 제품/파생 정답은 독립 reviewer 가명 ID 2개, 초기 판단, 불일치·조정·근거, `label_revision` |
| 분할 | split, source/speaker/episode 그룹 ID, 원본·파생 관계, holdout 잠금 revision, 오염 여부 |

원음·화면 원문·개인식별자는 Git에 넣지 않는다. Git에는 비식별 manifest와 합성 fixture만 두고, 실제 증거는 접근 통제된 저장소에서 참조한다. 해시 처리만으로 민감한 원문이 익명화되었다고 가정하지 않는다.

### 3.1 STT 정답

- 실제 발화를 먼저 그대로 전사한다. 읽히지 않는 구간은 위치와 함께 `unintelligible`로 표시한다. 의미를 추측해 완성하지 않는다.
- 한국어 Unicode NFC, 영문 대소문자, 공백·문장부호 처리를 `normalization_version`으로 고정한다. 원문 CER와 공백/문장부호를 제거한 정규화 CER를 모두 보고한다.
- 숫자·단위·부정어·방향어는 제거하지 않는다. 숫자 표기 동등성(삼/3 등)을 허용할 때는 별도 의미 평가에서 버전 있는 규칙으로 적용한다.
- `app`, `scope`, `setting`, `direction`, `negation`, `command`, `numeric_value`의 정답을 사람이 기록한다. 발화 자체가 모호하면 추가 질문이 정답이며 특정 goal로 강제하지 않는다.
- 무음/잡음/TV/TTS 혼입 여부를 표시한다. ASR이 배경 발화를 정확히 전사했는지와 제품이 이를 사용자 명령으로 채택했는지는 별도 평가한다.

### 3.2 UI 정답

- 정답은 클릭 가능한 실제 control의 집합이다. 작은 자식 text의 사각형과 클릭 부모의 사각형을 구분한다.
- 정답 target은 같은 시점의 package/window/role/resource/문맥/bounds로 식별한다. tree index 기반 node ID 하나를 환경을 넘는 영구 정답으로 쓰지 않는다.
- 실제 유효 대상이 여러 개면 허용 집합을 보존한다. 보이는 후보가 없거나 증거가 부족하면 `ABSTAIN`이 정답이다.
- ‘디스플레이’와 ‘잠금화면/홈 화면/화면 자동 꺼짐 시간’을 구분한다. `화면` 부분 문자열만으로 동등하다고 라벨링하지 않는다.
- 표시 정답에는 요청 관측뿐 아니라 **표시 시점**의 화면을 포함한다. 올바른 target의 오래된 위치도 실패다.
- 좌표 지표는 같은 정답 control을 선택한 경우에만 계산한다. 다른 control과 높은 IoU가 나와도 target 선택 성공으로 세지 않는다.

### 3.3 완료·복구 정답

두 축을 보존한다: `technical_outcome = CHANGED / NOT_ACHIEVED / UNVERIFIABLE`, `user_outcome = satisfied / needs_help / stopped / no_response`. `cancelled`, `expired`, `blocked`, `error` 종료 사유도 별도 필드다.

글자 크기 작업의 CHANGED 정답은 평가자가 별도 경로로 확인한 OS 설정 전후 값과 실제 대상/조작 관측에 근거한다. 수집기가 보내 준 값만 재사용하면 수집기 오류를 놓칠 수 있으므로, 실기기 시험에서는 별도 OS 읽기·동영상/화면 검수 등 근거의 독립성을 기록한다. 현재 제품이 완료를 선언할 때 요구하는 동일 slider 기준/변경값, FONT_SCALE, guide에 연결된 행동, 새 관측은 **runtime 증거 계약**이며 그 계약을 그대로 복사해 정답을 만들지 않는다. 지원 OS/OEM에서 값과 화면 의미가 일치하는지 먼저 검증한다.

실제 변경 여부와 **runtime이 가진 증거로 성공을 선언해도 되는지**도 분리한다. 독립 관측상 성공했어도 runtime에는 기준값/행동 연결이 없었다면 ‘근거 없는 성공 선언’은 실패다. 실제 결과를 알 수 없으면 UNVERIFIABLE이며 이진 task 성공률에 섞지 않는다. 사용자의 만족만으로 기술 결과를 바꾸지 않는다.

복구 정답에는 허용 대체 경로·시도 예산·재선택/중단 조건이 필요하다. 새 action이 추가된 graph를 평가할 때 기존 trace의 다음 화면을 그 action의 결과로 재사용하지 않는다. 그 edge의 실제 관측이 없으면 `NEEDS_DATA`다.

## 4. 독립 검수와 분할

1. 공개 benchmark는 배포자의 원본 정답·공식 채점 결과를 보존한다. 명백한 annotation 문제는 별도 errata와 수정 점수로 보고하며 원본 점수를 조용히 대체하지 않는다. 새 제품/파생 라벨은 수집·동의·범위 확인 → 두 검수자의 독립 판정 → 불일치 조정 → 근거·revision 고정 순서다. 최초 판정에서는 모델 예측을 가린다. 두 명 검수는 우리의 QA 절차이며 국제표준의 고정 인원 요구라고 표현하지 않는다.
2. 불일치를 강제로 성공/실패에 넣지 않는다. 제3의 조정자 또는 추가 근거가 없으면 `UNKNOWN`/검수 보류로 유지한다. 합의 전 일치율과 불일치율도 보고한다.
3. STT는 화자 단위, UI는 episode/연속 캡처/원본 시연 단위로 묶어 split한다. 동일 사용자가 여러 episode를 제공했다면 사용자 일반화 평가에서도 그룹을 분리한다. 증강·크롭·번역·합성 파생본은 원본과 같은 split이다.
4. 외부 데이터는 공식 split을 우선 유지하고 공식 test를 학습·튜닝에 넣지 않는다. 자체 수집 데이터는 화자/사용자/episode 군집과 필요한 test 정밀도로 수량을 정한다. 60/20/20은 과거 초안이며 v2의 기본 비율로 강제하지 않는다. 학습을 하지 않는 비교에는 train을 새로 만들 필요도 없다. 화자 ID가 공개되지 않으면 없는 ID를 만들어 화자 독립 검증이라고 주장하지 않는다.
5. test는 모델/graph/threshold 선택이 끝난 뒤 공개한다. test를 보고 수정한 후보의 그 test는 개발 자료가 되며, 새 locked test로 최종 평가한다. 이전 test의 사례는 지속 회귀용으로 보존할 수 있다.
6. 같은 기기·OS 안의 성능과 미관측 제조사/OS/앱 환경으로의 일반화는 별도 split/보고서로 평가한다. 지원 환경별 근거가 없는 평균 점수로 지원 범위를 넓히지 않는다.

## 5. 실제 수집 계획

아래 수량은 v1의 **수집 규모 참고안**이며 v2의 합격 요건이 아니다. 먼저 §9의 공개 평가셋을 확보하고, 공개 자료가 다루지 못하는 사용자·기기·명령 영역만 목적에 맞게 추가 수집한다. 완료 건수나 통계적 충분성을 뜻하지 않는다.

| 영역 | 초기 수집 단위 | 필수 층화·반례 |
| --- | --- | --- |
| 한국어 STT | 30명 이상 × 20발화 이상, 총 600발화 이상 | 60세 이상 화자 최소 10명 포함을 목표. 방향·부정·중지/재개·앱 범위·수치·혼용어와 쉼/잡음/작은 목소리. 무음/비음성 negative를 추가하고 별도 집계 |
| UI·완료 | 지원을 선언할 각 환경에서 확대·축소 × 아래 필수 시나리오 | 각 셀에 재현 가능한 evidence를 확보. 한 사람/기기의 반복으로 독립 표본 수를 부풀리지 않음 |
| graph 선택·복구 | 고정 catalog·정책별 유효/무효 후보 사례 | 미승인·비활성·hash 불일치·동점·미지원·다른 버전 재개·대체 경로·예산 소진 |

UI·완료 필수 시나리오: 정상 경로, 접근성 대체 경로, Display 부재+이름 유사 메뉴, 동명 복수 후보, 복수 slider, 미조작 대기, 반대 방향/다른 설정 조작, 최대/최소, 행동·값 누락, 오래된/중복 관측, 늦은 응답 중 스크롤/회전/앱 전환, pause/resume/cancel, 동의 거절/만료, 재시작 후 checkpoint 복원.

평가용 수집은 사용자 확인을 받은 뒤 수행한다. 기존 운영 녹음이나 화면을 자동으로 평가셋으로 가져오지 않는다. 연령 등 층화 정보는 별도 동의받은 최소 범주만 사용한다.

## 6. 지표 정의

모든 비율은 분자/분모와 제외 사유를 함께 보고한다. 분모 0은 0% 또는 100%가 아니라 `N/A`다.

| 지표 | 정의 |
| --- | --- |
| CER / WER | `(치환 + 삭제 + 삽입) / 정답 문자 수 또는 어절 수`. CER는 corpus 합계와 화자별 평균을 함께 보고, 빈 정답은 무음 지표로 분리 |
| STT 핵심 의미 일치 | 필요한 의미 슬롯을 모두 보존한 발화 / 의미 판정 가능한 발화. 인식 오류로 인한 무응답도 실패 포함 |
| 무음 환각률 | 무음/비음성 입력에 실질 단어를 생성한 건 / 해당 입력 건수 |
| Goal/Command 정확도 | 정답 분류·필수 slots·추가 질문 여부가 모두 맞는 요청 / 판정 가능한 요청 |
| Graph 선택 정확도 | 허용 선택/거절/모호성 결과와 일치한 건 / 검수된 선택 사례 |
| 잘못된 target 안내율 | 허용 집합 밖의 target 안내 / 실제 target을 제시한 안내. 대상 없음에 임의 target을 제시한 것도 분자에 포함 |
| Target coverage | 유효 대상이 있고 안내가 필요한 관측에서 올바른 target 안내 / 그 조건의 전체 관측. 보류·timeout도 분모에서 제거하지 않음 |
| 올바른 보류율 | 정답이 ABSTAIN인 사례에서 target을 제시하지 않은 건 / ABSTAIN 사례 |
| 오래된 안내 표시율 | 표시 시점의 화면/target과 불일치한 target 안내 / target 표시 사건 |
| Bounds 품질 | 올바른 control에 한해 IoU, 중심점 오차(px와 화면 대각선 대비 비율), control 밖 침범 비율. unit 변환과 실기기 결과 분리 |
| 방향 오류율 | 실제 목표 방향과 다른 안내 / 방향을 제시한 안내 |
| 완료 선언 중 오판 비율 | 독립 정답이 NOT_ACHIEVED인데 기술 성공을 선언한 건 / 결과를 독립 판정할 수 있는 기술 성공 선언. false discovery 성격의 지표이며 FPR과 구분 |
| 미완료에서의 성공 오탐률(FPR) | NOT_ACHIEVED인데 성공을 선언한 건 / 독립 정답 NOT_ACHIEVED episode |
| 근거 없는 성공 선언율 | runtime 성공 선언에 필수 증거가 없는 건 / 모든 기술 성공 선언. gold가 UNVERIFIABLE인 성공 선언도 별도 포함 |
| 완료 누락률 | 독립 정답 CHANGED인데 기술 성공을 반환하지 않은 episode / 독립 정답 CHANGED episode |
| 전체 시도 대비 검증 성공률(주 지표) | 정해진 지원 범위·시간/시도 예산에서 독립적으로 성공 확인한 episode / 사전 고정된 적격 실행 episode 전체. timeout·오류·판정불능을 조용히 제외하지 않음. 사용자의 정당한 중지 요청은 별도 제어 성공 지표로도 평가 |
| 판정 가능 사례의 기술 성공률(보조) | 독립 정답 CHANGED / 실제 결과 판정 가능 episode. 위 전체 분모 결과 및 취소·미지원·오류·UNVERIFIABLE 수/비율과 함께만 보고 |
| 복구 성공률 | 허용 예산 내 유효 경로/목표로 복구한 건 / 검수된 복구 필요 사례 |
| 사용자 결과 | satisfied/needs_help/stopped/no_response 분포. 기술 결과와 교차표로 보고 |
| 지연·자원 | STT·turn·관측→표시 지연 p50/p95, timeout/OOM, CPU/GPU 메모리, 실제 동시성. 성공 요청만의 지연과 전체 요청 결과를 함께 보고 |

샘플 점수 0.8 등의 matcher confidence는 확률이 아니다. state 점수와 target 점수를 분리하며, 확률 보정을 하지 않았다면 ‘80% 성공’으로 표현하지 않는다.

## 7. 인수·승격 정책

공개 표준의 평가 원칙과 제품별 합격선을 구분한다. 지원 환경·표본·고정 인수 기준이 부족하면 PASS 대신 NEEDS_DATA를 반환한다. 위험 통제의 명시적 위반은 그 상태에서도 FAIL로 판정할 수 있다.

### 7.1 즉시 실패 조건

- 우리 서버 밖으로 평가/운영 원음을 전송하거나 외부 STT로 자동 fallback하는 경로.
- 동의 범위를 벗어난 실제 음성/화면 수집·보관, 원음/민감 전사문이 요청 로그에 남는 경로.
- 필수 반례에서 잘못된 대상·방향 안내, 증거 없는 기술 성공, 취소 후 이전 guide 재사용, 미승인 graph 실행. 하나라도 재현되면 `FAIL`.
- 평가셋 누출 또는 출처·독립 검수·split 추적이 없는 데이터를 제품 gold로 보고한 경우.

### 7.2 품질 통과 조건

1. 필수 반례 회귀는 전부 통과해야 한다. 현재 H1~H3의 알려진 UI 결함은 통과 전까지 승격을 막는다.
2. 공개 STT 평가는 원래 benchmark 지표와 동일한 test를 사용한다. 모델 비교는 개발셋에서 설정을 고정한 뒤 paired test 차이·95% 구간·실패 사례를 보고한다. CER 10%·의미 일치 95%는 **근거 보강 전 보류된 내부 목표**다. 각 corpus의 난이도가 다른데 단일 CER로 모두 합격 처리하지 않는다.
3. UI 공개 point accuracy와 제품의 control/rectangle/시점/방향 정확도를 따로 보고한다. coverage 90%, 일반 오류 상한 1%도 **보류된 내부 목표**다. 알려진 critical 반례에는 오안내·근거 없는 완료 0건을 요구하되, 이것을 모집단 오류율 0%라는 보장으로 확대하지 않는다.
4. 지연은 실제 기기·네트워크·cold start·queue를 포함해 측정한다. STT p95 3초, turn p95 2초는 과거 UX 가설이며 사용자의 중단/재시도·작업 완료 시간과의 관계를 확인해 정한다. 현재 보편적 공인 한계값으로 사용하지 않는다.
5. 제품 인수 기준은 목표 사용자·지원 환경, 실패 영향, baseline, 최소 의미 있는 개선/허용 회귀폭, 신뢰수준·표본 설계·시간 예산을 test 공개 전에 기록해 고정한다. baseline보다 개선됐다는 사실만으로 안전한 제품이라 판정하지 않는다. 기준이 미고정이거나 필수 환경의 증거가 없으면 NEEDS_DATA다. critical 회귀를 평균 개선으로 상쇄하지 않는다.

독립 Bernoulli 표본에서 오류 0건일 때 단측 95% 상한은 `1 - 0.05 ** (1 / n)`이다. n=30이면 약 9.50%, n=300이면 약 0.994%다. 이는 **표본 정밀도 계산 예시**이며 1%를 공인 허용 위험으로 정하는 근거가 아니다. 같은 화자·episode의 여러 turn은 독립 표본으로 계산하지 않는다. 독립성이 없으면 화자/episode를 군집으로 추정하고 표본 수·방법·한계를 보고한다. 모든 오류가 0인 자료에 단순 bootstrap만 적용해 상한 0%라고 주장하지 않는다. 여러 subgroup 동시 보장을 주장하려면 다중 비교도 고려한다.

파일럿 600발화의 test 분할은 통계적 정밀도가 부족할 수 있다. 파일럿은 모델 후보·수집 절차를 점검하는 단계이고, 필요한 독립 test 표본을 추가 확보한 뒤 제품 승격을 판단한다. 고정 test 결과를 본 뒤 합격선을 완화해 같은 test에서 재승격하지 않는다.

### 7.3 평가 상태

- `PASS`: privacy·필수 회귀·품질·지원 환경·독립 검수 기준을 모두 충족.
- `FAIL`: 측정된 기준 위반 또는 재현 가능한 critical 결함.
- `NEEDS_DATA`: 증거/표본/실기기 전이/독립 검수 부족. 무회귀와 동의어가 아님.
- `ERROR`: 평가 도구·입력·환경 오류. 모델 성능 실패와 구분.

현재 판정: `agent` 작업 트리의 UI H1은 재현되어 FAIL, STT 후보 및 실제 제품 품질은 NEEDS_DATA다. 별도 `develop@860d785` 검증의 FE 71개·Backend 관련 46개 통과는 계약/코드 회귀 증거다. 원래 `agent`의 Backend 충돌, 휴대폰 미연결, 실제 음성 평가 미실시는 따로 남아 있다. 자동 평가·영속 수집 pipeline 완성으로 표현하지 않는다.

## 8. 실행 산출물과 역할

각 평가 실행은 `run_id`, manifest hash, 모델/graph/code revision, 실행 설정·장치, split, 지표별 분자·분모·구간, 제외/실패 사례, subgroup, 판정·검수자를 남긴다. 원천 record·라벨을 덮어쓰지 않고 revision으로 연결한다.

FE·Backend·AI1은 사실·관측·실행 결과를 생산한다. AI2는 데이터 재구성과 weak label·후보를 만들 수 있지만 자기 판정을 자동 정답으로 삼지 않는다. 독립 검수자가 gold를 확정하고 제품 담당자가 고정된 기준으로 승격을 판단한다. 새 graph는 구조 검사와 실제 edge 근거·held-out 회귀를 통과한 뒤 불변 version으로 게시한다. 기존 episode의 고정 version과 rollback 기준을 보존한다.

관련 문서: [STT 선정](./stt-model-selection.md), [UI 재검증](./reviews/2026-10-10-ui-highlight-revalidation.md), [AI1 정책](./ai-agent-1-runtime-policy.md), [AI2 계약](./ai-agent-2-graph-proposal.md).

## 9. 공개 기준으로 재구성한 평가 계획

### 9.1 ‘공적 기준’이 정해 주는 것과 정해 주지 않는 것

| 근거 | 적용할 원칙 | 이 근거만으로 주장할 수 없는 것 |
| --- | --- | --- |
| NIST AI RMF MEASURE 1.3, 2.1~2.5, 4.1 | 개발 담당자와 독립된 평가 관점, test·도구·지표 기록, 실제 배포 조건·대상 집단의 대표성, 일반화 한계와 사용자 피드백 | 특정 CER·정확도·표본 수가 공인 합격선이라는 주장 |
| NIST SCTK | reference와 hypothesis 정렬에 따른 ASR 오류 채점, 같은 입력·평가 조건에서 시스템 간 비교 | 문자 오류율 하나가 명령 안전성·사용자 만족을 보장한다는 주장 |
| 공개 benchmark 원문·공식 scorer | 외부 팀도 재현 가능한 정답·지표·분할 | 해당 benchmark가 우리 OEM·한국어·고령자·하이라이트 표시를 모두 검증한다는 주장 |

출처: [NIST AI RMF Core](https://airc.nist.gov/airmf-resources/airmf/5-sec-core/), [NIST SCTK 설명](https://github.com/usnistgov/SCTK/blob/master/doc/sctk.htm). NIST의 지침을 참고하는 것과 NIST 인증을 받은 것은 다르다. 본 문서는 외부 인증 결과가 아니다.

### 9.2 STT: 공개 실음성 우선 평가셋

| 우선순위·자료 | 실제 제공 근거·역할 | 분할·채점 | 한계와 확인 상태 |
| --- | --- | --- | --- |
| A · **FLEURS ko_kr** | 사람이 녹음한 한국어 낭독과 reference transcript. 공개 재현 비교 기준 | 공식 validation으로 설정 선택, test로 최종 비교. 원래 전사/정규화 프로토콜과 추가 한국어 CER를 이름을 달리해 보고 | 낭독이어서 짧은 자발적 설정 명령과 다름. 이번에 공식 카드 metadata만 직접 파싱: train 2,307 / validation 226 / test 382. 음성 전수 검사·추론 미실시 |
| A · **KsponSpeech / AI Hub 한국어 음성** | 한국어 자유 대화 원음·전사, 머뭇거림 등 실제 자발 발화 평가 | 배포본의 공식 eval_clean/eval_other 유지. 원 전사규약의 철자/발음 표기·간투사·공백 처리를 고정하고 CER 및 해당 protocol의 WER 계열 보고 | 조용한 대화 녹음이므로 TV·단말 마이크·긴 쉼의 충분한 대표성은 별도 확인. 공식 설명·관련 연구를 확인했으며 평가 음성은 아직 확보하지 않음 |
| B · **AI Hub 한국인 대화음성** | 연령·지역·음질 등 metadata가 있어 subgroup 보강 후보 | 고령층/방언/잡음 등 실제 분포와 화자 분리 가능성을 archive에서 확인한 뒤 사용 | 공식 설명에 고령층 분류가 있지만 그 존재만으로 원하는 표본 수·대표성을 보장하지 않음 |
| B · **AI Hub 고령자 근현대 경험 기반 스토리 구술** | 고령자 인터뷰 원음·전사 후보. 긴 발화·쉼·화자 특성 보강 | 필요한 음성/전사·연령 범주만 사용. 질문자와 대상 화자를 구분하고 화자 단위로 평가 | 경험 회상 도메인으로 설정 명령과 다름. 포털의 활용 분류 정확도를 STT 성능으로 옮기지 않음. 원음 archive·전사 정합성·사용 가능 범위 미검증 |

근거: [Google FLEURS](https://huggingface.co/datasets/google/fleurs), [ETRI/AI Hub 한국어 음성](https://aihub.or.kr/aihubdata/data/view.do?dataSetSn=123), [한국인 대화음성](https://www.aihub.or.kr/aihubdata/data/view.do?currMenu=115&dataSetSn=130&topMenu=100), [고령자 구술 자료](https://www.aihub.or.kr/aihubdata/data/view.do?aihubDataSe=data&currMenu=115&dataSetSn=71703&topMenu=100).

AI Hub 신청·사용 조건과 실제 배포 revision을 확보한 다음 원음/전사 해시·파일 수·누락·발화 경계를 검사한다. 공개 포털 설명을 읽은 것을 다운로드·평가 완료로 보고하지 않는다. 고령자 자료의 추가 심리/건강 라벨은 STT 평가 목적에 필요하지 않으므로 수집·추론에 넣지 않는다.

FLEURS 공개 feature 목록에는 `speaker_id`가 없다. `id`를 곧 화자 ID라고 가정하거나 382발화를 382명의 독립 화자로 계산하지 않는다. 공식 split을 유지하고 확인 가능한 sentence/source 군집, 화자 정보의 한계를 보고한다. 모델의 사전학습 corpus와 겹치지 않는지도 완전히 확인되지 않았으므로 공개 benchmark 점수만으로 미관측 사용자 일반화를 확정하지 않는다.

### 9.3 UI·실행: 서로 다른 능력을 따로 검증

| 공개 자료 | 사용할 정답과 평가 | Click 적용 조건 | 대체할 수 없는 평가 |
| --- | --- | --- | --- |
| **ScreenSpot** | 실제 screenshot·사람의 instruction·대상 bbox. 공식 point-in-box accuracy를 그대로 재현 | image-grounding 후보가 있을 때 mobile 및 Android 범위를 따로 보고. 원본 bbox 좌표 규약을 확인 | AX matcher에 screenshot만 주면 입력 계약이 다름. point 정확도는 사각형 품질·새 화면 유효성·실행 성공이 아님 |
| **AndroidControl** | 사람의 goal·단계 지시·행동·동시 screenshot/AX. step 대상과 행동 참조, 미관측 앱/작업 일반화 | Click과 겹치는 목표·환경을 **모델 결과를 보기 전에** 선별. 좌표→control 변환의 0개/복수 후보는 불확실 상태로 남김 | 시연 한 경로를 유일한 정답으로 강제할 수 없음. 폰트 drag의 전후 값·Click guide 행동 귀속·완료 oracle은 별도 |
| **AndroidWorld** | 실제 emulator와 task 초기화·상태 기반 성공 판정 코드 | 지원하는 작업을 동일 환경·초기 상태·예산에서 수행. Click 전용 작업을 추가하면 파생 평가라고 표시 | screenshot 행동 일치와 달리 상태 변화를 확인하지만, 한국어 Samsung 실기기·사용자 안내 이해도까지 검증하지 않음 |

근거: [ScreenSpot 저자 설명](https://github.com/njucckevin/SeeClick#gui-grounding-benchmark-screenspot), [공식 채점 코드](https://github.com/njucckevin/SeeClick/blob/main/pretrain/screenspot_test.py), [AndroidControl 공식 형식](https://github.com/google-research/google-research/tree/master/android_control), [AndroidWorld](https://github.com/google-research/android_world).

AndroidControl에서는 목표만 주는 다음 단계 선택과 `step_instruction`까지 주는 대상 찾기 점수를 나눈다. 후자 지시에는 정답 행동의 의미가 들어 있으므로, 이를 넣어 얻은 점수를 자율 계획 성능으로 보고하면 누출이다. 다음 화면·현재 정답 행동·검수 bbox도 입력에서 제외한다.

공개 자료의 원본 annotation과 제품용 추가 라벨은 다른 버전이다. 영어를 한국어로 번역하거나 AX를 임의 제거한 자료는 파생/합성 조건이며 실제 한국어 단말·자연 AX 누락과 분리 보고한다. 자료별 이용 조건도 원본 revision과 연결한다. AndroidControl의 이용조건 자료 간 불일치는 [기존 감사](./reviews/2026-09-20-android-control-data-review.md)에 남아 있어 대량 학습/재배포가 자동 허용된 것으로 판단하지 않는다.

### 9.4 공개 자료로 채울 수 없는 정답의 최소 범위

공개 데이터에는 Click의 승인 graph ID·version/hash, 한국어 결과 확인·추가 조절 프로토콜, 우리의 표시 시점이 원래 정답으로 들어 있지 않다. 이 부분을 ‘공식 데이터에서 이미 검증됨’이라고 주장할 수 없다. 필요한 제품 평가를 만들되 팀의 예측을 정답으로 쓰지 않는 방식으로 보완한다.

| 평가 대상 | 독립된 정답 근거 | 순환 평가 방지 |
| --- | --- | --- |
| 목표/방향/부정/중지 | 실제 사용자가 한 요청과 모호성, 제3자 전사·의미 판정 | goal classifier 출력이나 ASR hypothesis를 정답으로 복사하지 않음 |
| graph 선택 | 고정된 승인 catalog의 목표·선행조건·기기 호환성·증거에 맞는 허용 집합 | 현재 dispatcher가 골랐다는 이유만으로 정답으로 확정하지 않음. 복수 유효 경로 허용 |
| 하이라이트 | 표시 당시의 실제 화면과 조작 가능한 control, 독립 화면 검수 | matcher가 선택한 node와 그 bounds를 곧 정답으로 쓰지 않음 |
| 완료 | 요청 의미에 맞는 OS 상태의 독립 전후 관측과 부수 변경 여부 | runtime의 DONE, 사용자 ‘만족’, 시연 마지막 화면을 기술 성공 정답으로 쓰지 않음 |
| 중지/재개/추가 조절 | 서버 상태·새 episode·실제 UI의 일치, 취소 이후 추가 안내/조작 유무 | API 200 또는 단위 테스트 통과를 실기기 성공으로 복사하지 않음 |

라벨 지침은 개발자를 제외한 검수자 및 대상 사용자 관점으로 검토하고, 가능하면 외부 검수자에게 blinded sample 감사를 맡긴다. 평가자가 팀 안에 있다는 이유만으로 자동 무효는 아니지만 개발 출력과 독립된 evidence·판정·불일치 기록이 필요하다. 이는 아직 수행한 외부 평가를 뜻하지 않는다.

## 10. 의미 있는 평가인지 판별하는 실행 절차

### 10.1 채점 계약을 먼저 고정

각 run은 `claim`, `source/revision/split`, `eligible_population`, `input_view`, `oracle`, `normalizer/scorer_revision`, `budget`, `baseline`, `critical_errors`, `uncertainty_method`, `gate_origin`을 먼저 기록한다. `gate_origin`은 `published_protocol`, `product_risk_requirement`, `provisional_hypothesis` 중 하나다. 외부 지표를 사용하는 것과 외부 합격선을 적용하는 것은 다르다.

예: `claim=한국어 자발 발화 전사`, `test=KsponSpeech 공식 eval`, `oracle=배포자 전사`, `input=원음만`, `scorer=CER_NS+공식 전처리`라면 한국어 전사 비교를 뒷받침한다. 이 run의 결과를 `삼성 설정 변경 성공률`로 보고할 수는 없다.

### 10.2 STT 점수가 의사결정을 바꾸는지 확인

1. Qwen1.7B·Whisper large-v3 기준선, Qwen0.6B·turbo 효율 후보를 같은 file ID 순서로 비교한다. 모든 revision·전처리·VAD·언어·decode 설정을 저장한다.
2. 공개 corpus별 원래 metric과 한국어 정규화 CER를 구분한다. 원문 CER·공백 제거 CER·어절 WER·문장 완전 일치를 보고한다. 숫자/단위/부정어/방향어를 지워 점수를 개선하지 않는다. 참조 전사가 있는 timeout/빈 출력은 누락 결과로 채점하고 실행 실패도 별도 집계한다.
3. 실제 명령의 보조 평가는 `발화→정답 전사→goal`과 `발화→STT→같은 goal 모듈`을 비교해 STT가 추가한 의미 손실을 분리한다. 공개 대화에 설정 명령 정답이 없다면 임의로 goal을 붙여 ‘공개 명령 benchmark’라 부르지 않는다.
4. 의미 손실은 방향·부정·중지·대상/범위·수치별 confusion과 분자/분모를 낸다. CER가 낮아도 ‘키우지 마’를 ‘키워’로 바꾸면 critical 의미 오류다.
5. 같은 사례의 차이와 군집을 고려한 구간을 보고한다. CER는 전체 편집거리 합/문자 수를 주 지표로, 발화별 평균·화자/도메인별 결과를 함께 둔다. 불확실성 구간이 넓으면 승자를 확정하지 않는다. 지연 비교는 같은 실제 하드웨어·동시성에서 한다.

### 10.3 UI·완료 평가가 점수를 쉽게 부풀릴 수 없는지 확인

- **항상 보류** 기준선: 오선택은 적지만 coverage/전체 성공률이 낮아야 한다. 잘못된 target 비율 하나만 보면 이 기준선을 최고로 뽑는 오류가 생긴다.
- **항상 완료** 기준선: 빠르지만 미변경·반대 변경에서 성공 오탐이 드러나야 한다. 성공 응답 개수나 사용자 만족만 세면 이 기준선을 구별하지 못한다.
- **의도적으로 다른 메뉴를 감싼 정확한 사각형**: bounds가 정교해도 target 선택 실패로 계산해야 한다.
- **정답 중심을 포함하는 화면 전체 사각형**: point accuracy만으로 통과할 수 있으므로 overlay IoU·침범·control identity를 별도 평가해야 한다.
- **지연된 올바른 응답**: 요청 당시에는 맞아도 표시 시점 화면이 바뀌면 오래된 안내 실패다. 단위 matcher test만으로 검증할 수 없다.
- **상태만 이미 변경된 사례**: 실제 기술 결과, 이번 안내/행동에 귀속되는 변경, runtime에 성공 증거가 충분했는지를 별도 채점한다.

UI 시험은 정상·모호·target 없음·stale 화면 조건과 제조사/OS/locale별로 나눈다. offline 단계 정확도, 시간 제한 내 실제 task 성공, 잘못 안내한 횟수, 도움 개입 횟수, 최종 사용자 만족을 각각 보고한다. 중지 응답 시간을 빨리하는 것과 전체 작업 시간이 짧은 것은 다른 개선이다.

### 10.4 표본 수와 합격선을 정하는 근거

- 공개 benchmark 비교는 공식 test 전체를 기본으로 한다. 비용상 표본만 평가하면 추출 규칙·seed·범위·구간을 사전 공개하고 전체 점수처럼 보고하지 않는다.
- 추가 제품 데이터는 개발용 파일럿으로 분산·오류 유형을 조사한 뒤, 최소 의미 있는 개선과 허용 오류 상한에 맞춰 test 규모를 정한다. 30명이 20번 말한 600개를 600명의 독립 근거로 계산하지 않는다.
- 예를 들어 정확도 비율을 95% 구간에서 대략 ±5%p로 추정하려는 단순 독립 표본·최대분산 정규근사는 `1.96² × 0.25 / 0.05² ≈ 385`다. 이는 계획 예시일 뿐 희귀 오류·군집·고령자 subgroup의 충분성을 보장하지 않는다.
- 희귀한 잘못 안내/완료에는 §7의 이항 상한 또는 적합한 군집 설계를 사용한다. 반복 test 확인에 따른 선택 편향도 고려한다. 통계적 유의성, 실제로 유용한 개선, 허용 가능한 위험은 각각 판단한다.
- 한국어 고령자 사용성은 실제 대상 사용자가 사전 정한 작업을 수행하는 관측이 필요하다. 성공 여부·소요시간·오조작·도움 요청·이해/만족을 비교하고 과제 순서/학습 효과를 통제한다. 공개 음성의 연령 metadata만으로 사용성을 증명하지 않는다.

## 11. 이번 재검증에서 직접 확인한 것

[재현 스크립트](./reviews/evidence/audit_public_evaluation_contracts.py)와 [실행 결과](./reviews/evidence/2026-10-10-public-evaluation-contracts.json)에 원본 URL·byte 수·SHA-256을 남겼다.

| 실행 확인 | 결과 | 증거의 범위 |
| --- | --- | --- |
| FLEURS 공식 카드의 ko_kr metadata 파싱 | train 2,307 / validation 226 / test 382, 16kHz audio schema, 화자 ID field 없음 | 카드 metadata 확인. 음성 archive 전수 검사나 STT 실행이 아님 |
| AndroidControl 공식 split 파일 전체 + 현재 loader | train 13,603 / validation 137 / test 1,543, 합계 15,283, 주 split 중복 없음. 모든 ID 분류 확인 | 실제 공개 split의 호환성. 음성/화면 의미 품질이 아님 |
| AndroidControl test 하위 집합 | IDD 721 / category_unseen 700 / app_unseen 631 / task_unseen 803, 합집합 1,543 | 집합들이 겹치므로 2,855개의 독립 test로 합산 금지 |
| ScreenSpot 공개 scorer의 실제 point-in-box 조건 | 합성 정답 중앙에서 정확한 box(IoU 1)와 화면 전체 box(IoU 0.04)의 중심 모두 통과 | 지표의 측정 범위 실험. ScreenSpot 모델 점수·annotation 오류 주장 아님 |
| 현재 UI 반례 및 후보 gate 실험 | 기존 오선택 4/4, 프로세스 내 gate 0/4, 정상 이름 3/3 유지 | 합성 개발 회귀. 공개/실기기 gold·제품 적용 아님 |

로컬 AndroidControl importer는 실제로 존재한다. 다만 현재 출력은 goal/step_instruction/action_type이고 screenshot·AX·좌표는 내보내지 않으므로 이 결과만으로 UI grounding을 평가할 수 없다. `--official-splits`가 없으면 80/10/10 자체 분할을 사용하는 구현이어서 **공식 benchmark 재현에는 옵션을 필수로 취급**한다. 이전 문서의 ‘importer 없음’ 표현은 현재 코드와 달라 정정한다.

이번에는 모델 가중치 다운로드·ASR 추론·AI Hub 원음 다운로드·AndroidWorld 실행·실기기 품질 평가·외부 검수자를 통한 gold 승격을 수행하지 않았다. 따라서 확보된 것은 **평가 설계의 외부 근거와 일부 파일/채점 의미의 검증**이다. 실제 품질 결과는 여전히 NEEDS_DATA이며, corpus·모델·실기기별로 run을 채운 뒤 판단한다.

재현 예시(원본 공개 파일을 먼저 해당 경로로 내려받고 실행):

```bash
PYTHONDONTWRITEBYTECODE=1 python3 docs/reviews/evidence/audit_public_evaluation_contracts.py \
  --fleurs /private/tmp/click-fleurs-readme.md \
  --splits /private/tmp/click-android-control-splits.json \
  --subsets /private/tmp/click-android-control-test-subsets.json \
  --scorer /private/tmp/click-screenspot-test.py \
  --output /private/tmp/click-public-evaluation-audit.json
```
