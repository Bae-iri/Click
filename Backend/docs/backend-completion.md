# AI 독립 백엔드 구현 및 인수 현황

기준일: 2026-09-12. 1~4주 차 기반에 5~8주 차 중 AI·기기·클라우드가 없어도 구현할 수 있는 서버 기능을 추가했다. **서버 코드·로컬 검증 완료 범위와 실제 서비스의 운영 완료는 구분한다.**

## 이번에 추가한 기능

| 영역 | 서버 구현 |
|---|---|
| 승인 | 본인 세션·버전·화면 해시·작업·상태·대상·행동에 결합, 120초 만료, 성공 반영과 함께 소비, 취소·삭제 시 제거 |
| 응답 검증 | 승인 없이 CONFIRM_REQUIRED 행동 금지, 승인 후 다른 대상/행동/작업/상태 거절, 범위를 벗어난 좌표·잘못된 안전 등급 거절, 거절 의사는 서버에서 차단 응답으로 강제 |
| 이력 | 본인 세션 목록, 최대 100건 커서 페이지, 세션 상태·처리 시각 조회, 종료된 세션 삭제 |
| 피드백 | 종료된 본인 세션에 1~5점·정해진 이유 코드 저장/조회/수정, 세션당 한 건 |
| 동의 | 최신 목적별 동의 조회, 철회 시 이벤트 삭제와 누적 개인화 프로필 초기화, 사용자 DB 잠금으로 이벤트·철회 직렬화 |
| 계정 | 이메일 인증 요청/확인/상태, 비밀번호 분실 요청/재설정, 일회용·만료 토큰, 재설정 후 모든 로그인 토큰 폐기 |
| 운영 | 별도 운영 토큰으로 지표 조회, DB 테이블뿐 아니라 컬럼 존재 검사, 만료 데이터 정리, 상태 점검·백업·분리 DB 복구 명령 |
| 호환 경로 | 기존 `/api/v1/sessions`는 기본 서버에서 제거. 명시적인 테스트용 `create_app(enable_legacy=True)`에서만 사용 |

## 새 API 계약

모든 사용자/세션 API는 기존 Bearer 인증을 사용한다. 회원 이메일 인증 여부는 별도 상태이며 현재 로그인·안내 사용을 강제 차단하는 정책은 적용하지 않았다.

| Method / Path | 설명 |
|---|---|
| GET `/api/v1/agent/sessions?limit=20&after=...` | 본인 세션 이력. session_id 오름차순, `next_cursor`를 다음 `after`로 전달 |
| DELETE `/api/v1/agent/sessions/{id}` | 종료/취소/만료 세션과 연결된 turn·checkpoint·승인·피드백 삭제. 진행 중이면 먼저 취소 |
| PUT `/api/v1/agent/sessions/{id}/feedback` | `rating`: 정수 1~5, `reason_code`: helpful/wrong_target/unclear/too_slow/other 또는 null |
| GET `/api/v1/agent/sessions/{id}/feedback` | 본인 피드백 조회 |
| GET `/api/v1/users/{id}/consents` | 목적별 최신 동의 기록; 미기록 목적은 반환 목록에 없음 |
| POST `/api/v1/auth/email/request` | 로그인한 계정으로 인증 토큰 발송 요청, 202 |
| POST `/api/v1/auth/email/verify` | `token`으로 인증 완료, 204 |
| GET `/api/v1/auth/email/status` | 로그인한 사용자의 `verified` 상태 |
| POST `/api/v1/auth/password/forgot` | `email` 입력. 계정 존재 여부와 관계없이 동일한 202 본문 |
| POST `/api/v1/auth/password/reset` | `token`, `new_password` 입력, 성공 204 및 기존 access/refresh 폐기 |
| GET `/ops/metrics` | 별도 `OPS_TOKEN`의 Bearer 인증. 요청 수·5xx 수·평균 시간·상태 코드별 건수 |

기존 오류 envelope 유지. 잘못되거나 만료된 계정 작업 토큰은 400, 승인 불일치는 409 + `X-Conflict-Code: CONFIRMATION_INVALID`, 유효하지 않은 AI 안내는 502다. 자세한 스키마는 [OpenAPI](openapi.json)를 따른다.

## 앱의 승인 처리

1. 서버가 실제 행동 승인을 요청할 때 `guide_command.proposed_action`에 tap/drag/scroll이 있고 `metadata.confirmation`에 id·expires_at·guide_command_id·version이 있다. 확인 UI는 observe_only로 표시한다.
2. 사용자가 승인하면 같은 session_id, 새 request_id, 직전 expected_version, 새로 수집한 같은 화면 정보, `user_confirmation: "accepted"`, `confirmation_id`를 보낸다. raw_goal은 보내지 않는다.
3. 본문 재전송은 ID·버전·confirmation_id까지 동일하게 유지한다. 성공한 요청의 정확한 재전송은 동일 결과를 반환하지만 새 요청에서 이미 소비한 승인은 사용할 수 없다.
4. 화면이 바뀌었거나 승인이 만료되면 승인값 없이 새 요청·새 화면으로 안내를 다시 받아 확인한다. 캡처 시각만 바뀐 것은 화면 변경으로 취급하지 않는다.
5. 사용자의 거절은 언제나 안전하게 수용하여 BLOCKED로 끝낸다. 모호한 목표를 다시 묻는 응답은 실제 행동 승인이 아니므로 proposed_action·승인 ID가 없을 수 있다. 그 경우 새 목표를 입력한다.

화면 해시는 **클라이언트가 보낸 관찰이 같은지** 확인한다. 실제 기기의 화면이나 캡처 시각을 증명하지 않는다. 화면을 허위로 보내는 기기나 AI의 의미적 판단까지 해결한 것으로 간주하지 않는다.

이번 계약에는 confirmation_id와 proposed_action이 추가됐다. 이전 버전에서 진행 중이던 요청은 본문 정규화 해시가 달라질 수 있으므로 배포 전 앱 계약을 함께 갱신하고 기존 진행 작업은 취소 후 새 세션으로 시작한다.

## 개인정보·삭제 보장 범위

- 저장된 응답은 개인화 적용 전의 중립 응답이다. 반환 시 최신 동의를 재검사한다.
- 이벤트 기록·동의 철회·응답 개인화 조회는 사용자 행을 기준으로 직렬화한다. 철회 완료 이후 새로 개인화하는 응답에는 이전 프로필을 사용하지 않는다. 이미 사용자에게 전달된 응답은 회수할 수 없다.
- 철회 시 이벤트와 누적 UI 프로필만 정리한다. 계정·세션 전체 삭제는 사용자 탈퇴, 개별 작업 삭제는 이력 삭제 API를 사용한다.
- 사용자 탈퇴는 DB FK로 계정 작업 토큰·이메일 인증·세션·승인·피드백까지 연쇄 삭제한다.
- 이벤트의 session_id를 지정하면 본인 세션이어야 한다. 이벤트 metadata·원본 이미지·외부 텍스트 전송 제한은 4주 차 정책을 유지한다.
- 백업 파일은 실데이터를 포함하므로 접근 권한·암호화·폐기 주기 및 삭제 요청 이후 백업 복구 시 재삭제 절차가 필요하다. 저장소에는 백업을 추가하지 않는다.

## AI가 준비되면 연결할 위치

`app/clients/runtime_agent.py`가 서버와 AI의 변환 경계다. `AgentTurnService.engine_factory`는 테스트에서 다른 구현으로 교체할 수 있다. 생성자는 `session_store`를 받고 `handle_turn(request)`는 `AgentTurnResponse`를 반환한다.

`tests/test_backend_completion.py`의 `ContractAgent`는 실제 runtime·모델·graph를 호출하지 않고 서버의 승인·이력 흐름을 검증한다. 운영용 가짜 AI 모드는 추가하지 않았다. 실제 AI의 재개 상태는 기존 checkpoint 어댑터에 맞추거나 버전이 있는 새 checkpoint 계약을 합의해야 한다. 현재 코드의 task graph·목표·상태 인식까지 새로운 AI가 자동 호환된다는 뜻은 아니다.

AI 팀과 확인할 항목: 요청/응답 스키마, proposed_action, task/state ID, 대상 좌표, checkpoint 형식·버전, timeout·재시도, 지원 작업별 완료 조건. 새 graph에 맞춘 실제 기기 시나리오가 필요하다.

## 실행·운영 인수

1. 기존 DB에는 `python -m alembic upgrade head`를 적용한다. 새 revision은 `20260912_04`이며 승인·피드백·계정 작업·이메일 인증 테이블을 추가한다.
2. 메일은 `.env.example`의 SMTP_HOST/PORT/FROM/USERNAME/PASSWORD를 설정한다. STARTTLS를 사용한다. 미설정이면 요청은 503이며 원문 토큰을 응답·로그로 대신 출력하지 않는다. 이번 개발에서는 테스트 발송기를 사용했고 실제 메일은 보내지 않았다. 이메일 템플릿·앱의 토큰 입력 UI와 실메일 전달은 통합 검증 대상이다.
3. OPS_TOKEN을 32자 이상의 독립 난수로 설정한다. 비어 있으면 지표 API는 404다. 사용자 로그인 토큰과 공유하지 않는다. 지표는 프로세스 메모리이며 재시작하면 초기화된다.
4. 주기 실행기로 `python scripts/maintenance.py`를 실행한다. DATA_RETENTION_SECONDS 기본값은 개발용 86400초이며 실제 보존 정책은 팀이 정한다. 세션 이력·피드백도 이 기간 이후 함께 정리된다. 서버를 실행했다고 정리 작업이 자동 등록되지는 않는다.
5. `python scripts/monitor_check.py`는 MONITOR_BASE_URL의 health/ready와 선택적 metrics를 검사한다. 실패 시 종료 코드 1이다. 운영 플랫폼에서 주기 실행·알림 연결이 필요하다. 지표 오류율은 프로세스 시작 이후 누적값이며 시계열 대시보드나 최근 5분 오류율을 대체하지 않는다.
6. DATABASE_URL을 명시하고 `python scripts/database_backup.py backup <새 파일>`을 실행한다. SQLite는 일관된 backup API와 integrity/FK 검사, PostgreSQL은 pg_dump custom 형식을 사용한다.
7. SQLite 복구: `python scripts/database_backup.py restore-sqlite <백업> --new-database <새 DB 파일>`. 기존 파일 덮어쓰기는 거절한다.
8. PostgreSQL 복구: 별도 빈 DB의 RESTORE_DATABASE_URL을 설정하고 `python scripts/restore_postgres.py <백업>`을 실행한다. 앱 DB와 같은 대상·이미 테이블이 있는 대상은 거절한다. pg_restore를 한 트랜잭션으로 수행하고 복구 뒤 readiness·인수 검증을 해야 한다. 실제 PostgreSQL 도구 실행은 아직 검증하지 못했다.

단일 worker 구성을 유지한다. 인증 rate limit과 지표가 프로세스 메모리이므로 다중 worker/서버 전환 전 공유 제한·집계가 필요하다. 클라우드 생성·비용 발생·상시 배포는 수행하지 않았다.

## 검증과 남은 조건

최종 로컬 결과: **Backend 91개, AI-Agent 27개 테스트 통과.** 실제 서버 재시작 검증과 SQLite 백업·복구 검증을 완료했다.

로컬 테스트·실행 결과는 [verification.md](verification.md)의 최신 기록을 따른다. `python scripts/load_check.py`는 폐기 가능한 SQLite와 테스트 AI로 4개 동시 요청·40개 요청을 실행한다. 실제 AI 응답시간이나 운영 용량을 나타내는 수치는 아니다.

남은 외부 조건: 실제 AI와 앱별 task 확정, Android 화면·인증·승인·이력 UI 연결, 실제 PostgreSQL/컨테이너 검증, 메일 계정·전달 검증, 팀의 보존/성능 기준, 운영 배포·모니터링 알림·백업 복구 연습. 이 항목까지 확인해야 전체 서비스 운영 완료로 판단할 수 있다.
