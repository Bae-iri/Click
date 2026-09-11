# 3주 차 — 안내 세션 영속화와 생명주기

신규 `POST /api/v1/agent/turn` 경로의 DB 저장·복구·중복/동시 요청·조회·취소·만료를 구현했다. 레거시 `/api/v1/sessions`는 기존 메모리 구현을 유지하며 이 영속화의 대상이 아니다.

## 완료한 개발

| 작업 | 구현 내용 | 확인 방법 |
|---|---|---|
| B06 세션 DB | agent_sessions, agent_turns, agent_checkpoints + migration 20260911_03 | SQLite upgrade/downgrade/upgrade, metadata check |
| B07 runtime 복구 | 요청마다 격리된 runtime에 checkpoint 복원 | 서버 프로세스 종료·재실행 후 목표 생략하여 진행 |
| B08 멱등성 | 사용자별 request_id 고유키, 정규화한 본문의 SHA-256, 중립 응답 저장 | 동일 요청 재사용, 변경된 본문·세션 재사용 거절 |
| B09 동시성·소유권 | 짧은 DB 트랜잭션으로 lease 확보, 결과 commit 시 version·lease 재검사 | 같은 DB를 쓰는 두 앱 인스턴스의 경쟁·취소·탈퇴·lease 만료 테스트 |
| B10 조회·취소·만료 | 본인 세션 조회, 취소, 유휴 만료, 정리 명령 | 취소 이후 진행 차단, 만료 410, terminal 데이터 정리 |

실제 PostgreSQL·Docker·원격 배포 검증은 기존 환경 결정 대기 상태다. PostgreSQL 검증 스크립트와 CI에 새 schema와 세션 검증을 반영했으며 실행하지 않은 항목을 완료로 기록하지 않는다.

최종 로컬 결과: Backend **51개**, AI-Agent **27개** 테스트 통과. 실제 서버 프로세스 종료·재실행과 SQLite migration 왕복 검증도 통과했다. [검증 기록](verification.md) 참조.

## 앱 API 계약 변경

모든 API에 2주 차 Bearer 인증을 사용한다.

| API | 요청 | 응답 |
|---|---|---|
| POST /api/v1/agent/turn | 첫 요청: 기존 본문, expected_version 생략 또는 0 | 기존 응답 + session_version |
| POST /api/v1/agent/turn | 후속 요청: 새 request_id, 같은 session_id, 직전 session_version을 expected_version으로, 새 관찰 | 다음 안내 + 증가한 session_version |
| GET /api/v1/agent/sessions/{id} | 본인 session_id | status, version, task/state ID, processing, requires_goal, requires_fresh_observation, 시각 |
| POST /api/v1/agent/sessions/{id}/cancel | `{"expected_version": 2}` | CANCELLED 상태·새 version |

시각은 UTC Unix seconds이다. 조회는 과거 화면 좌표를 포함한 guide를 반환하지 않는다. 진행 중 세션에는 requires_fresh_observation=true가 반환되며 앱은 현재 화면을 새로 수집해야 한다. 서버가 화면의 실제 촬영 신선도를 증명하는 것은 아니며, 기기 timestamp 정책은 후속 통합 작업이다.

```json
{
  "request_id": "turn-002",
  "session_id": "session-001",
  "expected_version": 1,
  "accessibility_tree": {
    "package_name": "com.android.settings",
    "screen_width": 1080,
    "screen_height": 2400,
    "nodes": []
  }
}
```

빈 nodes는 형식 예시일 뿐이다. 실제 진행은 현재 화면의 유효한 tree/OCR을 보내야 한다. 목표가 모호해 아직 runtime state가 없으면 조회의 requires_goal=true를 보고 새 raw_goal도 전송한다.

Android에는 인증·버전 전달이 아직 연결되지 않았다. Gateway에 session_version 저장 및 expected_version 전송을 추가하고, 취소나 더 최신 응답 이후 도착한 낮은 버전의 안내는 화면에 표시하지 않아야 한다.

## 중복·충돌 규칙

- 응답 유실 재전송은 같은 request_id와 동일한 본문(expected_version 포함)을 유지한다.
- 첫 요청의 기본값·alias는 Pydantic 정규화 후 해시한다. 본문 원문은 저장하지 않는다.
- 같은 ID·같은 요청은 이미 저장된 최신 처리 결과를 재사용한다. 정상 재전송은 version을 증가시키지 않는다.
- 같은 사용자의 request_id를 다른 본문/세션에 재사용하면 409다.
- 이미 다음 turn이 진행된 뒤 과거 요청을 재전송하면 409 STALE_REPLAY다. 예전 화면의 안내를 다시 반환하지 않는다.
- 동시에 처리 중이면 409 TURN_IN_PROGRESS다. 같은 요청을 제한된 간격으로 재시도한다. 다른 요청이라면 상태 조회 후 새 화면·현재 version으로 요청한다.
- COMPLETED/BLOCKED는 최종 요청의 정확한 재전송만 허용한다. CANCELLED/EXPIRED는 guide 재전송도 차단한다.
- 저장된 응답에는 개인화 프로필을 포함하지 않는다. 반환할 때마다 최신 동의를 확인하므로 철회 후 재전송 응답의 문구·개인화 메타데이터는 달라질 수 있다. 안내 결과와 version은 동일하다.

HTTP 오류 envelope는 2주 차 형식이다. 세부 409 원인은 `X-Conflict-Code` 헤더로 구분한다.

| 상태/코드 | 의미 | 앱 처리 |
|---|---|---|
| 401 / 403 / 404 | 인증·소유권·없는 세션 | 로그인/사용자 상태 확인 |
| 409 REQUEST_ID_REUSED | 다른 본문·세션에 ID 재사용 | 새 논리 요청이면 새 ID |
| 409 STALE_VERSION / STALE_REPLAY | 오래된 상태의 요청 | 조회 → 새 화면 → 새 요청 |
| 409 TURN_IN_PROGRESS | 유효한 처리 lease 존재 | 동일 요청 제한 재시도 |
| 409 TURN_SUPERSEDED | 취소·만료·탈퇴·다른 worker 처리로 결과 무효 | 안내 적용 금지, 상태 조회 |
| 409 SESSION_CLOSED / SESSION_CANCELLED | 종료·취소된 세션 | 필요하면 새 session_id |
| 409 CHECKPOINT_INCOMPATIBLE | 배포로 graph 또는 checkpoint 형식 변경 | 기존 작업 취소 후 새 세션 |
| 410 SESSION_EXPIRED | 유휴 세션 만료 | 새 session_id |

## 저장·동시성 구조

- agent_sessions: 소유자, 상태, version, task/state, 마지막 guide ID, 시간, lease token/만료.
- agent_turns: 사용자/request_id 고유키, session FK, 본문 hash, 처리 version, 중립 응답. 처리 중에는 응답이 비어 있다.
- agent_checkpoints: session PK/FK, schema/graph version, 재개에 필요한 runtime 상태.
- SQL compare-and-swap으로 lease를 잡고 트랜잭션을 끝낸 뒤 AI를 실행한다. 외부 호출 동안 DB 잠금을 유지하지 않는다.
- 결과는 동일 lease token, 미만료 lease·session, 같은 version, 진행 중 상태일 때만 commit한다. 상태·checkpoint·응답은 하나의 트랜잭션으로 저장한다.
- 요청마다 runtime을 새로 만들기 때문에 실패한 runtime의 메모리 상태가 다음 요청에 섞이지 않는다.
- 프로세스가 죽으면 lease 만료 후 같은 요청을 재시도할 수 있다. 오래된 worker 결과는 commit할 수 없다. 외부 AI 호출 자체가 한 번만 실행된다는 보장은 없으며 **상태 반영이 중복되지 않게** 하는 구조다.

## 개인정보·보존 범위

원본 목표, tree 전체, 스크린샷, OCR, UI label 샘플은 저장하지 않는다. 화면 fingerprint는 원래 연결 문자열 대신 SHA-256으로 바꿨다. checkpoint는 task·전이·실패 노드 ID·시도 횟수·슬라이더 수치 등의 최소값이다. 응답의 source_text·target_label·일부 provider 진단 필드는 제거한다. 해시는 암호화가 아니며 식별자와 생성된 안내 메시지는 여전히 데이터 관리 대상이다.

사용자 삭제는 FK CASCADE로 세션·요청·checkpoint도 지운다. 취소는 checkpoint와 응답 payload를 즉시 비우되 ID/hash 기록은 정리 전까지 보존한다. 이 변경은 신규 영속 경계의 최소화이며 외부 AI 전송·레거시 trace를 포함한 전체 마스킹 완료를 뜻하지 않는다.

## 개발용 시간 정책과 정리

- `AGENT_SESSION_IDLE_SECONDS`: 기본 1800초. 성공한 새 turn에서 연장, 재전송/조회로 연장하지 않음.
- `AGENT_TURN_LEASE_SECONDS`: 기본 60초. heartbeat 없음. 이 시간을 넘긴 결과는 반영하지 않고 재시도하도록 함.
- 아래 정리 명령의 terminal 보존 기본값: 86400초(24시간). 개발용 기본값이며 제품 보존 정책은 미확정.

```powershell
# 실제 개발 DATABASE_URL이 설정된 Backend 디렉터리에서 실행
python scripts/cleanup_agent_sessions.py --retention-seconds 86400
```

만료는 turn/조회/취소 시 검사하므로 별도 배치가 없어도 만료 세션을 진행할 수 없다. 정리 명령은 유휴 세션을 EXPIRED로 바꾸고, 보존 기간이 지난 terminal 세션을 관계 데이터와 함께 삭제한다. 배포 환경의 주기 실행은 아직 설정하지 않았다. 정리 후에는 이전 request/session ID 기록도 사라지므로 ID는 UUID 등으로 계속 새로 생성한다.

안내 turn 경로의 공유 상태는 DB로 옮겼지만 레거시 안내와 인증 rate limit은 여전히 프로세스 메모리다. 따라서 현재 배포의 single worker 설정은 유지한다.
