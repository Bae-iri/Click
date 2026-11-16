# Click

익숙하지 않은 모바일 앱에서 사용자의 목적과 현재 화면을 이해하고, 다음 행동을 실시간으로 안내하는 모바일 사용 지원 AI Agent입니다.

Click은 대신 눌러주지 않습니다. 사용자가 말로 원하는 것을 요청하면 지금 화면에서 눌러야 할 곳을 하이라이트하고 음성으로 알려주며, 사용자가 직접 누른 결과를 확인해 다음 단계로 이끕니다.

## 시연 영상

[![Click 시연 영상](https://img.youtube.com/vi/Xqq0O440Lsg/hqdefault.jpg)](https://youtu.be/Xqq0O440Lsg)

▶ [YouTube에서 보기](https://youtu.be/Xqq0O440Lsg)

## 주요 기능

- **음성·텍스트 요청**: "글자 크게 해줘"처럼 말하면 목표(Goal)와 필요한 값(slots)을 확정합니다.
- **실시간 화면 안내**: Accessibility Tree로 현재 화면을 읽고, 다음에 누를 UI 요소를 오버레이로 하이라이트하고 TTS로 안내합니다.
- **행동 검증과 복구**: 사용자 행동 후 화면을 다시 관찰해 성공 여부를 확인하고, 다른 화면으로 벗어나면 승인된 Task Graph 안에서 경로를 다시 잡습니다(Re-plan).
- **Vision/OCR fallback**: Accessibility 정보만으로 대상을 찾지 못하면 사용자 동의를 받아 기기 안에서 OCR을 수행합니다(ML Kit 한국어 모델). 원본 이미지는 외부로 전송하지 않으며, 같은 단계에서 3회 실패하면 수동 안내로 돌아갑니다.
- **안전 장치**: 결제·비밀번호 화면 차단, 개인정보 마스킹, 위험 행동은 사용자 확인 후 진행합니다.
- **Task Graph 개선 파이프라인(AI-Agent 2)**: 실제 실행 데이터를 단계별로 재구성·라벨링하고 새 graph 후보를 평가해 승인 후 배포합니다.

## 기술 스택

| 영역 | 사용 기술 |
| --- | --- |
| Frontend | Android (Java, minSdk 26 / targetSdk 34), AccessibilityService, Overlay, ML Kit Text Recognition (Korean), Kakao 로그인 |
| Backend | Python 3.11+, FastAPI, SQLAlchemy, Alembic, PostgreSQL |
| AI-Agent | Python, Task Graph 기반 런타임(Observe → Guide → Verify → Re-plan), Graph Proposal 파이프라인 |
| Infra | Docker Compose, Render |

## 빠른 시작

```bash
# 1. DB (PostgreSQL)
docker compose up -d

# 2. Backend
cd Backend
python -m pip install -e ".[test]"
cp .env.example .env   # 필요한 값 입력
# 실행 방법은 Backend/README.md 참고

# 3. AI-Agent 테스트
cd ../AI-Agent
python -m pytest -q

# 4. Android 앱 빌드
cd ../Frontend
sh ./gradlew :app:assembleDebug
```

영역별 상세 안내: [Frontend](Frontend/README.md) · [Backend](Backend/README.md) · [AI-Agent](AI-Agent/README.md)

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

카탈로그 v2.4.0에는 Android 설정 110개와 쿠팡 71개, 총 181개 Goal의 실행 안내가 있습니다. 설정은 변경 검증 50개·화면 이동 21개·직접 진행 안내 39개입니다. 쿠팡은 화면 이동 8개·확인 후 장바구니 담기 및 반영 검사 1개·직접 진행 안내 62개입니다. 상품·주문 선택, 검색어·개인정보 입력과 거래 확정은 사용자가 쿠팡에서 진행하며 사용자 답변과 기술 검증 결과를 구분합니다. 전 기종·쿠팡 앱 버전의 실기기 검증 완료를 뜻하지 않으며 정부24 graph는 아직 없습니다. 새 태스크를 사용하려면 Backend와 Android 앱을 함께 업데이트해야 합니다. [쿠팡 실행 안내](docs/tasks/coupang.md), [Goal 카탈로그](docs/goal-type-keyword-catalog.md), [AI2 감사 안내](AI-Agent/src/ai_agent/graph_proposal/README.md#db-기록-처리)를 참고합니다.

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
