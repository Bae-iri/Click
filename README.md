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
   │  ├─ runtime/           # AI-Agent(1): Goal → Observe → Guide → Verify → Re-plan
   │  ├─ learning/          # AI-Agent(2)로 확장할 데이터·Graph Proposal 골격
   │  └─ task_graph/        # Task Graph 구조와 Registry
   ├─ task_graphs/          # Graph seed
   └─ tests/
```

## Responsibilities

### Frontend

- 사용자 입력(음성 또는 텍스트)과 안내 UI
- Android Accessibility Tree 수집
- 화면 위 UI 요소 하이라이트 및 단계별 안내 표시
- 사용자 행동 후 변경된 화면 정보 전송

### Backend

- 사용자 요청과 현재 화면 정보를 받아 Task 실행 세션 관리
- API 요청·응답 검증 및 Task 상태 관리
- 화면 정보와 Agent 판단 결과의 전달 조율
- Safety Gate, 개인정보 마스킹, 완료 여부 및 실행 로그 관리
- 데이터베이스와 외부 AI Agent 연동

### AI-Agent(1): 사용자 작업 수행

- 사용자의 자연어 요청에서 최종 Goal 파악
- 현재 화면에서 다음 행동 결정
- 행동 안내 및 사용자 행동 이후 결과 검증
- 예상하지 못한 화면이나 잘못된 행동 발생 시 Re-plan
- 전체 실행 과정의 trace 생산 및 승인된 Task Graph 사용

### AI-Agent(2): Runtime Data / Graph Proposal

- 전체 runtime 데이터의 episode·step 재구성 및 success/fail 결과 라벨링
- 성공·실패·사용자 우회 경로 분석과 내부 학습·평가 데이터셋 생성
- 신규 Task Graph 추가 및 기존 State·Transition·selector 업데이트 후보 생성
- 변경안 평가, 승인에 필요한 근거 제공, graph version 및 release 관리

상세 설계: [전체 구조](docs/ai-agent-architecture.md) · [AI-Agent(1)](docs/ai-agent-1-runtime.md) · [AI-Agent(2)](docs/ai-agent-2-graph-proposal.md)

위 두 역할은 목표 설계이며, 현재 `runtime/*`에 사용자 실행 흐름이 구현되어 있고 `learning/*`의 graph proposal 및 dataset 생성은 골격 단계다.

## MVP Scope

지원 앱은 쿠팡, Android 설정 앱, 정부24이며 대표 Task는 상품 구매, 설정 변경, 행정 서류 발급입니다.

기본 처리 흐름은 다음과 같습니다.

```text
사용자 Goal 입력
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
