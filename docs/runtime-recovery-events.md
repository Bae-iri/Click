# AI1 경로 재선택과 실행 이벤트 저장

구현일: 2026-10-10. 배포 전에 Alembic `20261010_12` 적용이 필요하다.

## 경로 재선택

AI1은 같은 graph에서 대상 제외·재시도·alternate_transition을 먼저 검토한다.
AI1의 `render_policy.backend_handoff.type=task_graph_reselection_required`가 있을 때만
Backend가 재선택을 수행한다. 사용자 대기, 관측 부족, 위험 action 거절에는 자동 재선택하지 않는다.

- 기존 목표·앱에 맞는 active/approved graph에서 이미 실패한 본문 hash를 제외한다.
- 후보가 없거나 동순위 후보가 모호하거나 graph가 잘못됐으면 BLOCKED와 명시적인 중단 사유를 보존한다.
- 자동 graph 교체는 최대 2회, 세션의 자동 복구 연쇄에서 확인된 실패 조작은 최대 12회다.
  graph 교체로 전체 실패 횟수를 초기화하지 않는다.
- 새 graph에도 같은 의미의 transition이 있으면 실패한 대상과 transition 시도 이력을 이어받는다.
  이미 처리한 행동 event 키도 유지한다. 이전 snapshot·pending action·안내·승인은 재사용하지 않는다.
- 새 graph pin, 새 episode, 새 checkpoint, response와 execution event를 한 트랜잭션으로 확정한다.
- 응답은 IN_PROGRESS + observe_only + 새 화면 요청이다. 기존 FE가 새 episode/version을 저장하고
  `request_fresh_observation` 정책에 따라 화면을 재수집한다. 첫 재관측부터 새 graph를 실행한다.
- 정확한 요청 재시도는 같은 결과를 반환한다. 다른 request_id의 이전 episode 요청이나 늦은 승인은 409다.
- 사용자가 새 목표로 replace/adjust한 경우는 새 작업이므로 복구 예산을 새로 시작한다.

승인된 다른 graph가 실제 DB에 있어야 다른 경로로 바뀐다. 코드가 임의 graph를 생성하거나
검증되지 않은 후보를 활성화하지 않는다.

## 영속 실행 이벤트

`runtime_events`는 session version을 sequence로 사용한다. session/sequence 고유 제약과
기존 request hash/lease를 통해 중복·순서 역전·늦은 결과 저장을 막는다.
재선택 이벤트는 실패가 발생한 기존 episode/graph hash에 연결하고 교체 결과도 함께 기록한다.
AI1 turn, typed pause/resume/cancel, 결과 응답·추가 조절 명령을 기록한다.

저장하는 증거:

- state/transition 식별자, action 종류, target 역할과 hash.
- guide/attempt/전후 observation hash, 수치 control의 전후 값.
- verification status, 실제 시도 여부, 기술적 완료 여부, 허용된 사유 코드.

UI 원문 label, 자유 텍스트, OCR, screenshot, password는 저장하지 않는다.
메모리 TraceLogger와 별개이며 화면 전체를 재생할 수 있는 기록은 아니다.
이벤트 저장 실패 시 해당 turn의 response/checkpoint/재선택 변경도 롤백한다.
사용자·세션 삭제 시 이벤트와 소비 결과를 cascade 삭제한다.

기본 보존 기간은 7일이다. 만료 이벤트는 AI2 소비에서 제외되며 아래 purge 작업으로 물리 삭제한다.
현재 명령은 운영자가 실행하거나 스케줄러에 등록해야 한다. 자동 스케줄은 생성하지 않았다.

## AI2 비동기 소비와 운영 명령

Backend 디렉터리에서 운영 대상 DB의 DATABASE_URL을 설정한 상태로 실행한다.
Render의 일반 PostgreSQL URL은 psycopg3 형식으로 변환한다.

```sh
python scripts/agent2.py process --limit 100
python scripts/maintenance.py
```

소비 명령은 사용자 안내 요청과 별도로 실행한다. 온라인 Backend는 AI2 패키지를 import하거나
외부 AI2 응답을 기다리지 않는다. `runtime_event_consumptions`의 consumer/event 고유 receipt로
재실행·동시 실행의 중복 반영을 막는다. sequence 순으로 처리하며 event마다 짧게 commit한다.

학습 동의가 해당 event 기록 전에 있어야 AI2가 label을 생성한다. 동의가 없거나 과거 데이터이면
not_eligible receipt만 남긴다. 학습 동의 철회 시 소비 결과를 삭제한다.
검증된 조작만 verified_success/verified_failure로 분류하며 수동 화면 이동은 observed_progress,
사용자·관측 대기는 waiting, 증거 부족은 unknown이다. 사용자 만족 응답을 기술적 성공으로 대체하지 않는다.

이 소비기는 최소 evidence label 생성·저장까지 연결한다. 모델 학습, 새 graph 자동 승인·게시,
외부 training job 실행은 하지 않는다. 배포 후 Render Cron Job 또는 기존 별도 scheduler에
소비 명령(예: 1분 간격)과 purge 명령(예: 하루 1회)을 등록해야 지속 처리된다.

## 검증 범위

승인 후보 재선택, 그래프/실패 예산 소진, 실패 대상 유지, 재시도와 이전 episode 차단,
DB event 저장 실패 롤백, 수치 변경 증거, 보존·삭제, AI2 동의·철회·중복 소비를 로컬에서 검증한다.
PostgreSQL 운영 DB와 실제 Android 기기에서의 검증은 배포 후 필요하다.
