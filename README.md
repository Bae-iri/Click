# Click

익숙하지 않은 모바일 앱에서 사용자의 목적과 현재 화면을 이해하고, 다음 행동을 실시간으로 안내하는 모바일 사용 지원 AI Agent입니다.

## Repository Structure

```text
Click/
├─ Frontend/
│  ├─ app/
│  │  └─ src/
│  │     ├─ main/
│  │     │  ├─ java/com/click/
│  │     │  │  ├─ core/          # 공통 설정, 권한, 오류 처리
│  │     │  │  ├─ data/          # API, DTO, Repository 구현
│  │     │  │  ├─ domain/        # 모델, Repository 계약, Use Case
│  │     │  │  ├─ service/       # Accessibility, Overlay, STT·TTS 서비스
│  │     │  │  ├─ ui/            # 앱 내부 화면과 Overlay UI
│  │     │  │  └─ util/          # 공통 유틸리티
│  │     │  ├─ res/               # layout, drawable, values 등 Android 리소스
│  │     │  └─ assets/            # 로컬 정적 자산
│  │     ├─ test/                 # 단위 테스트
│  │     └─ androidTest/          # Android 통합·UI 테스트
│  └─ docs/                       # Frontend 구현 상세 문서
├─ Backend/
│  ├─ app/
│  │  ├─ api/v1/endpoints/  # 클라이언트가 호출하는 API endpoint
│  │  ├─ core/              # 환경설정, 공통 예외, 인증 등 핵심 설정
│  │  ├─ db/                # DB 연결, 세션, 마이그레이션 설정
│  │  ├─ models/            # DB 모델 및 영속화 대상 도메인 객체
│  │  ├─ schemas/           # API 요청·응답 스키마
│  │  ├─ repositories/      # 데이터 조회·저장 추상화
│  │  ├─ services/          # Task 실행, 화면 관찰, 안내·검증 조율
│  │  └─ clients/           # AI Agent, 외부 서비스 연동 client
│  ├─ tests/
│  └─ docs/
└─ AI-Agent/
   ├─ src/ai_agent/
   │  ├─ runtime/           # AI-Agent(1): 전달 Graph → Observe → Guide → Verify → Re-plan
   │  ├─ graph_proposal/    # AI-Agent(2): 데이터·Graph 후보·평가·release
   │  ├─ telemetry/         # 공통 관측 계약·진단 producer
   │  └─ task_graph/        # Task Graph 구조와 Registry
   ├─ task_graphs/          # Graph seed
   └─ tests/
```

## Responsibilities

### Frontend

- 사용자 입력(음성 또는 텍스트), Goal Type·slots·Command Type 확정과 안내 UI
- Android Accessibility Tree 수집
- 화면 위 UI 요소 하이라이트 및 단계별 안내 표시
- 사용자 행동 후 변경된 화면 정보 전송

### Backend

- 사용자 요청과 현재 화면 정보를 받아 Task 실행 세션 관리
- API 요청·응답 검증 및 Task 상태 관리
- DB에서 사용자 요구에 맞는 승인 Task Graph 선택·버전 고정·복원 및 AI1 전달
- 화면 정보와 Agent 판단 결과의 전달 조율
- Safety Gate, 개인정보 마스킹, 완료 여부 및 실행 로그 관리
- 데이터베이스와 외부 AI Agent 연동

### AI-Agent(1): 사용자 작업 수행

- Backend가 선택한 Task Graph 본문·reference·확정 목표·실행 상태 수신 및 정합성 검사
- 현재 화면에서 다음 행동 결정
- 행동 안내 및 사용자 행동 이후 결과 검증
- 전달된 graph 안에서 Re-plan; graph 밖 복구는 Backend에 재선택/지원 불가 처리 요청
- 전체 실행 과정의 trace 생산 및 승인된 Task Graph 사용

### AI-Agent(2): Runtime Data / Graph Proposal

- 실제 runtime 데이터·통제된 기기 시연의 수집, episode·step 재구성 및 근거 기반 라벨링
- 성공·실패·사용자 우회 경로 분석과 내부 학습·평가 데이터셋 생성
- 공개 참고 자료의 출처·변환·적용 범위 관리와 우리 환경의 정답 데이터 검수 workflow
- 신규 Task Graph 추가 및 기존 State·Transition·selector 업데이트 후보 생성
- 변경안 평가, 승인에 필요한 근거 제공, 승인된 새 graph version의 DB 반영 및 release 관리

상세 설계: [전체 구조](docs/ai-agent-architecture.md) · [Backend Graph 전달](docs/backend-task-graph-dispatch.md) · [AI-Agent(1)](docs/ai-agent-1-runtime.md) · [AI-Agent(2)](docs/ai-agent-2-graph-proposal.md)

2026-10-10 기준: [STT 개인정보·한국어 후보 선정](docs/stt-model-selection.md), [UI 하이라이트 재검증](docs/reviews/2026-10-10-ui-highlight-revalidation.md), [실제 품질·정답 데이터 기준 v1](docs/ai-quality-evaluation.md). UI 오선택은 재현되었고 제품 gold·실기기 평가는 미완료다. 문서의 구현 상태와 목표 설계를 구분한다.

두 역할은 `runtime/*`와 `graph_proposal/*`로 분리하고 공통 증거는 `telemetry/*`에서 관리한다. Agent 2의 동의 기반 DB 처리·단계 재구성·실패 분석·데이터 분리·기존 경로 우선순위 후보·replay 평가·승인/롤백을 구현했다. 실제 제품 gold·독립 기기 holdout은 남아 있다. [현재 구현·실행](AI-Agent/src/ai_agent/graph_proposal/README.md)을 참고한다.

## 정답 데이터와 개발 순서

**ColorBench는 검수한 경로·복구 평가의 보조 자료, MobiAgent의 MobiFlow는 의존관계·분기·완료 규칙의 설계 참고로 사용한다.** 우리 graph 선택과 실제 작업 완료의 정답은 대상 기기·앱의 실행 증거와 독립 검수로 만든다. AI2의 자동 라벨·graph 후보를 곧바로 제품 정답이나 승인 graph로 사용하지 않는다.

개발은 평가 계약 → 실제 증거 수집 → 제품 정답·baseline → graph 후보·회귀 평가 → 승인·배포 순서로 진행한다. 공개 참고 실험은 수집과 병행한다. 담당 역할·산출물·완료 조건은 [Task Graph 정답 데이터·개발 계획](docs/task-graph-data-plan.md), 근거는 [재재검증 보고서](docs/reviews/2026-09-20-task-graph-gold-fitness-audit.md)에 있다.

## MVP Scope

장기 목표는 쿠팡, Android 설정 앱, 정부24의 사용 안내입니다. 현재 카탈로그 v2.3.0에는 Android 설정 110개와 쿠팡 71개, 총 181개 Goal이 있습니다. 실행 승인 범위는 설정 110개로, 설정 변경 검증 50개·설정 화면 이동 21개·사용자가 직접 진행하는 안내 39개입니다. 쿠팡 71개는 분류 전용이며 정부24 실행 graph는 아직 없습니다. 직접 진행 안내는 사용자 답변과 기술 검증 결과를 구분하며, 기종별 실제 실행이 모두 검증됐다는 뜻은 아닙니다. 새 태스크를 사용하려면 Backend와 Android 앱을 함께 업데이트해야 합니다. 전체 목록은 [Goal 카탈로그](docs/goal-type-keyword-catalog.md), 배포 후 DB·목표 선택 확인은 [AI2 감사 안내](AI-Agent/src/ai_agent/graph_proposal/README.md#db-기록-처리)를 참고합니다.

아래는 합의한 설계 흐름입니다. Backend typed graph 선택·주입 일부가 연결되어 있으며, 범용 DB graph loader와 legacy raw-goal 경로 정리는 후속 작업입니다.

```text
사용자 요구 → FE Goal·Command 확정
	→ Backend DB Task Graph 선택·복원
	→ AI1에 Graph·목표·실행 상태 전달
	→ 현재 화면 관찰
	→ 다음 UI 요소 결정
	→ Safety Gate 확인
	→ 화면 하이라이트 및 행동 안내
	→ 사용자 행동 후 재관찰
	→ 완료 검증 또는 Re-plan
```

Accessibility Tree를 기본 정보원으로 사용하고, Tree만으로 UI 요소를 파악하기 어려운 경우에만 사용자 동의 후 Vision을 fallback으로 사용합니다. 외부 전송이 필요한 화면 데이터는 개인정보 마스킹 이후에만 처리합니다.

## Development Notes

- `Backend`는 API, 도메인 로직, 저장소, 외부 연동을 분리해 구현합니다.
- 위험 행동은 자동 실행하지 않고 사용자 확인을 거칩니다.
- 비밀번호·인증번호 자동 입력, 결제·송금 자동 수행, 개인정보 자동 입력은 MVP에서 제외합니다.
- 기술 스택과 배포 방식은 팀 합의 후 각 디렉터리의 설정 파일에 반영합니다.
