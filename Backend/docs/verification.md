# 첫 주 산출물 검증 기록

## AI 독립 백엔드 추가 검증 (2026-09-12)

- Backend 전체 **91 passed**, 기존 httpx deprecation warning 1건. AI-Agent 전체 **27 passed**.
- AI를 호출하지 않는 계약 테스트 19개: 승인 소비·정확한 재전송, 승인 누락/다른 ID/화면/목표/대상/만료/취소 거절, 잘못된 좌표 미저장, 본인 이력·페이지·피드백·삭제.
- 이메일 인증·일회용 토큰·만료·목적 분리, 비밀번호 복구 후 access/refresh 폐기, 동시 재설정 1회 성공, 메일 실패 토큰 무효화 검증. 실제 메일은 발송하지 않음.
- 동의 철회 시 이벤트 삭제·프로필 초기화·추가 이벤트 차단, 사용자 탈퇴 시 승인·피드백 CASCADE, 운영 지표 별도 인증 검증.
- SQLite 백업 → 별도 파일 복구 → 데이터 확인, 기존 대상 덮어쓰기 거절 검증.
- 최종 로컬 fake AI 부하: 동시 4개·총 40개 요청, **40개 모두 HTTP 200**, 실행 1.331초, p95 283.84ms, 최대 1191.54ms. 일회성 로컬 측정이며 운영 SLA나 실제 AI 성능 보장이 아님.
- 새 migration `20260912_04` 포함 SQLite upgrade/downgrade/upgrade 및 Alembic check 통과. 실제 서버 HTTP 인증·안내·프로세스 재시작·재전송·재개·취소 검증 통과.
- 기본 서버에서 레거시 API 제외, 기존 호환 테스트는 명시적으로 enable_legacy=True로 수행. OpenAPI는 현재 기본 서버 기준 23개 path. 코드·스크립트 구문 검사와 git diff --check 통과.
- 실제 PostgreSQL 검증 스크립트에도 이력·피드백·승인·이메일 인증을 추가했다. 현재 PC에 Docker/psql/pg_dump/pg_restore가 없어 실제 실행하지 않았다. PostgreSQL 백업·복구 명령도 미실행이다.

인수 범위 및 연결해야 할 외부 환경은 [backend-completion.md](backend-completion.md)에 기록했다.

## 4주 차 추가 검증 (2026-09-12)

- Backend 전체: **72 passed**, 기존 httpx deprecation warning 1건.
- AI-Agent 전체: **27 passed**.
- 신규 검증 21개: 민감 입력 필드·텍스트 제거, HTTP/DB 저장값의 합성 개인정보 미노출, 동의 유무와 관계없는 원본 이미지 외부 호출 차단, embedding 전송 용어 제한, provider 형식 오류·장애·예산 만료 fallback.
- trace allowlist·건수·TTL, 이벤트 metadata·화면 이름 제한, 오래된 UI 이벤트 삭제 검증.
- 지연된 runtime의 504 응답·늦은 결과 미저장·정확한 재시도 성공, 실행 슬롯 포화 503 검증.
- state 이름을 변경한 graph에서도 진행률 계산 일치 확인.
- 실제 Uvicorn 프로세스의 인증·안내, 종료·재실행 후 재전송·재개·취소, 안전 로그 검증 통과.
- SQLite migration upgrade → downgrade → upgrade 및 Alembic check 통과. 새 DB 스키마 변경은 없음.
- OpenAPI 18개 path 재생성: 503/504와 Vision·이벤트 제약 설명 반영. `git diff --check` 통과.

실제 외부 AI 호출은 사용하지 않고 테스트 대역으로 전송 내용·실패 처리를 검증했다. 실제 PostgreSQL·Docker·Android·클라우드 검증은 미실행이다. 구현 범위와 제한은 [week4.md](week4.md)를 따른다.

## 3주 차 추가 검증 (2026-09-11)

- Backend 전체: **51 passed**, 기존 httpx deprecation warning 1건.
- AI-Agent 전체: **27 passed**.
- 신규 세션 검증 17개 포함: 재시작 복구·재전송, 다른 본문 ID 재사용, stale version/replay, 소유권, 취소·만료·종료 상태, DB 최소 정보 저장, 사용자 삭제 cascade, runtime 실패 rollback, 버려진 lease 복구, schema/graph 불일치, 개인화 동의 철회, 모호한 목표 재입력.
- 두 독립 앱 인스턴스 + 같은 SQLite 파일 DB: 동시 첫 요청, 동일·다른 후속 요청의 경쟁, 처리 중 취소·lease 만료·세션 만료·탈퇴에서 결과 commit 차단 확인.
- 실제 Uvicorn 자식 프로세스: 로그인·안내 실행 → 프로세스 종료 → 같은 DB로 재실행 → 세션 조회·동일 응답 재전송·목표 생략 후속 turn·취소 통과.
- SQLite migration 20260911_03까지 upgrade → downgrade → upgrade 및 Alembic check 통과.
- OpenAPI 18개 path, 세션 조회·취소·expected_version·session_version·410 반영. 스크립트 구문·문서 링크·git diff --check 확인.
- PostgreSQL 검증 스크립트에도 재시작·응답 재사용·두 앱의 취소 경쟁 시나리오 추가.

**미실행:** 실제 PostgreSQL·Docker/클라우드 배포·Android 기기 연동·외부 AI 호출. 위 검증은 로컬 SQLite 기반 결과이며 PostgreSQL의 실제 동시성 보장을 검증한 것으로 해석하지 않는다. 노션에는 직접 쓰지 않았고 사용자에게 대화에서 요약을 제공한다.

## 2주 차 추가 검증 (2026-09-11)

- Backend: **34 passed**, 기존 httpx deprecation warning 1건.
- AI-Agent: **27 passed**.
- 실제 로컬 Uvicorn: health/ready/OpenAPI, 회원가입 → Bearer 인증 → 사용자 조회 → 샘플 turn 통과.
- SQLite migration: 기존 revision부터 인증 migration까지 upgrade → downgrade → upgrade 및 Alembic check 통과.
- 비밀번호·토큰 해시 저장, 만료·변조·누락 토큰 거절, refresh 회전·동시 요청 단일 성공, 로그아웃·비밀번호 변경·탈퇴 시 토큰 폐기 검증.
- 사용자 API·turn·레거시 세션의 타인 접근 거절, 파일 DB의 로그인·폐기 상태 재시작 후 유지 검증.
- Content-Length 및 chunked body 한도, tree 노드 개수, 오류 응답·로그의 합성 민감값 미노출 검증.
- 실제 서버 로그에서 trace_id 출력과 비밀번호·access token 미출력 확인.
- OpenAPI: 16개 path, Bearer security와 공통 오류 스키마 반영.
- Markdown 링크, JSON/YAML 파싱, dev.ps1 구문, git diff --check 확인.

**미실행:** 실제 PostgreSQL, Docker 빌드·Compose 기동, GitHub Actions, 원격 개발 서버 배포, Android 인증 화면 연동. 현재 환경에 Docker/PostgreSQL 실행 도구와 배포 대상 정보가 없어 준비된 스크립트·설정의 실제 검증은 남아 있다.

아래는 첫 주 당시 기록으로 보존한다.

검증일: 2026-09-11. Windows / Python 3.14, 기존 설치 환경 사용.

## 실행 결과

| 확인 | 결과 |
|---|---|
| 변경 전 Backend pytest | 19 passed |
| 변경 후 Backend pytest | 22 passed |
| AI-Agent pytest | 27 passed |
| 임시 SQLite migration upgrade → downgrade → upgrade | 통과 |
| Alembic check | No new upgrade operations detected |
| 실제 Uvicorn HTTP /health, /ready | 통과 |
| 실제 HTTP /openapi.json | turn API 포함 확인 |
| 실제 HTTP 샘플 turn | 200, 설정 홈의 display 대상 안내 확인 |
| 실제 HTTP 사용자 생성·조회 | 201 생성, 동일 사용자 조회 확인 |
| DB 장애·테이블 누락 | ready 503, health 200 테스트 통과 |
| DB 장애 메시지 | 합성 비밀값 미노출 테스트 통과 |
| OpenAPI 생성 | 11개 path, 코드에서 생성 |

실제 서버 smoke 검증은 별도 임시 DB·포트·자식 프로세스로 수행했다. 검증 후 종료·정리했으며 상시 서버는 남기지 않았다. 외부 AI 키는 smoke 자식 프로세스에서 비웠다.

샘플 tree에 설정 제목을 포함하여 화면 분류에 필요한 맥락을 제공했다. 메뉴 항목 하나만 전달하면 현재 runtime이 디스플레이 내부 화면으로 판단해 fallback을 반환할 수 있다. 실제 기기에서는 전체 관련 화면 맥락을 보내야 하며 화면 분류의 강건성은 통합 검증 대상이다.

## 검증 환경 버전

fastapi 0.141.1 / pydantic 2.13.5 / sqlalchemy 2.0.52 / alembic 1.19.1 / uvicorn 0.52.4 / pytest 8.4.2 / httpx 0.28.1.

기존 TestClient에서 httpx 관련 deprecation warning 1건이 발생했다. 테스트 실패는 없으며 이번 작업에서 의존성 버전을 변경하지 않았다. 새 환경에 의존성을 다시 설치한 검증은 수행하지 않았다.

## 아직 검증하지 않은 범위

- 실제 PostgreSQL 및 PostgreSQL JSON/JSONB 차이
- 실제 Android 기기와 전체 지원 앱·task
- 실제 외부 AI provider 호출·비용·지연
- 인증·소유권·세션 영속화·개인정보 전체 파이프라인
- 운영 배포·부하·백업·복구

위 항목은 서버 완성까지의 후속 backlog에 포함되어 있다. 이번 검증은 첫 주 설계 산출물과 로컬 개발 기반의 완료 증거다.
