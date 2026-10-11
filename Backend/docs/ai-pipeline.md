# AI Pipeline

## 확정한 책임과 목표 흐름

FE는 Goal Type·slots·Command Type을 확정한다. **Backend가 DB에서 요구에 맞는 Task Graph를 선택·고정·복원하고, AI1은 전달받은 graph를 실행한다.** AI2는 실제 실행 데이터를 수집·재평가해 graph 후보와 검수 자료를 만들고 독립 평가·승인을 거친 새 version을 DB에 게시한다.

정답과 개발 순서는 [Task Graph 데이터 계획](../../docs/task-graph-data-plan.md)을 따른다. ColorBench·MobiFlow는 경로·규칙의 보조 참고이며 Backend 후보 graph 선택의 정답표가 아니다. 선택 평가에는 확정 goal·환경·catalog snapshot·정책별 허용 graph 집합/미지원/모호성 정답을 사용한다.

상세 기준은 [Backend Graph 선택·전달 계약](../../docs/backend-task-graph-dispatch.md), [FE 계약](../../docs/fe-goal-command-contract.md), [AI1](../../docs/ai-agent-1-runtime.md), [AI2](../../docs/ai-agent-2-graph-proposal.md)다.

```text
FE 확정 Goal·Command + 화면·행동
→ Backend 인증·typed 계약·세션/명령 검증
→ Backend DB graph 선택 또는 고정 graph 복원
→ graph 본문·reference·goal·parameters·실행 상태를 AI1에 전달
→ AI1 정합성 검사 → Observe/Normalize/Classify → 직전 step Verify
→ 완료 확인 또는 graph 내부 Re-plan → Transition/Target 선택
→ Safety/Guide → Backend 출력 검증·저장 → FE 표시
→ 사용자 조작·새 관측
```

pause/cancel은 Backend가 화면 추론 없이 상태와 pending guide를 처리한다. AI1이 전달 graph로 복구할 수 없으면 Backend에 사유를 반환해 재선택/재시작 또는 지원 불가 처리를 요청한다. AI1은 DB graph 검색·수정이나 Goal 재분류를 하지 않는다.

## 단계별 소유권과 인수 기준

| 단계 | 소유자 | 입력 → 출력 | 확인 조건 |
| --- | --- | --- | --- |
| Goal/Command | FE | 발화/context → 확정 Goal·slots·명령 | 모호성 질문, 미지원 처리 |
| Request/Command | Backend | typed 요청 → 실행/제어 분기 | 인증·멱등성·version·episode |
| Graph Selection | Backend | goal·환경 + DB → graph 본문/reference·parameters | 승인·호환성·우선순위, 후속 고정 version 복원 |
| Runtime Input | AI1 | 주입 graph·목표·상태 → 실행 입력 | schema/hash/goal/checkpoint 일치 |
| Observation/Classification | AI1 | tree/동의된 Vision + graph → nodes·state | 유효 좌표·관측 시각·모호성 |
| Verify/Re-plan | AI1 | 직전 step·새 관측 → 결과·graph 내 복구 | target에 묶인 완료 근거, 실제 시도 집계 |
| Match/Safety/Guide | AI1 + Backend 계약 집행 | transition·UI → guide/confirmation/blocked | target 유효성, 승인 바인딩, 안전 범위 |
| Graph Update | AI2 | 실행 이벤트 → 라벨·후보·평가·DB 새 version | 독립 근거, 미평가/UNKNOWN 구분, 승인·회귀 |

## 현재 코드와 전환 항목

2026-10-10 코드 확인: typed 시작/후속 요청은 DB selector가 schema/hash/목표 metadata를 검증하고 본문을 decode한 graph를 `RuntimeAgentAdapter`에 주입한다. episode의 version/hash는 `select_pinned()`로 복원한다. legacy raw-goal typing/lookup, 전체 환경 호환 검증, 임의 slot 보존은 남아 있다. 현재 Backend 충돌 4개로 통합 검증은 차단되어 있다. [품질·gold 기준](../../docs/ai-quality-evaluation.md)을 따른다.

Week 4의 graph 기반 progress, 실행 시간/동시성 제한, catalog 기반 embedding 외부 전송, 입력 redaction·진단 allowlist는 [week4.md](week4.md)가 기준이다. 현재 AI2의 graph learner·dataset builder·validator는 골격이며 후보/평가 저장 테이블이 있다는 사실과 자동 graph 개선 구현을 구분한다.

다음 연결 작업:

1. 기존 typed schema·Command Router·selector에 환경·catalog·정책별 선택 fixture와 실제 근거를 연결한다.
2. 구현된 DB 본문 loader·고정 version·adapter 주입을 충돌 정리 후 통합 재검증하고 사용자 parameter 보존을 보완한다.
3. AI1의 Goal Typing·registry 선택 호출을 제거하고 전달 입력 검증·graph 실행으로 시작한다.
4. checkpoint와 progress가 고정된 전달 graph·검증된 parameters를 사용하게 맞춘다.
5. FE DTO·OpenAPI·다중 turn 계약 fixture를 함께 전환한다.
6. 실행 이벤트를 AI2가 재구성하고 독립 제품 gold로 평가한 새 DB graph version을 Backend가 선택하는 흐름을 검증한다. 공개 참고·weak label·candidate의 승인 상태를 제품 artifact와 혼용하지 않는다.
