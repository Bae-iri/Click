# 텍스트 요청 404 및 배포 확인 (2026-10-09)

## 확인 결과

- `origin/develop` fetch 및 `pull --ff-only` 완료. 기준 커밋은 `1ac0668d2c65e2d6c7f8005ced04a3c897ae3650`.
- `https://click-db.onrender.com/openapi.json`에는 enqueue, claim, agent commands가 없음. 세 경로에 POST를 보내 모두 HTTP 404 확인.
- 이후 사용자가 Render에서 `1ac0668` 수동 배포를 실행했다. 관리 화면에서 기존 배포 커밋은 `0c1c527`, 연결 저장소는 `Bae-iri/Click`, 브랜치는 develop, 자동 배포 Off로 확인했다. 배포 후 공용 서버 OpenAPI에 세 경로 모두 등록되었고 `/ready`는 `{"status":"ready","database":"ok"}`를 반환했다. 위 404 관측은 재배포 전 결과다.
- 현재 develop에는 세 라우트가 등록되어 있음. 실제 서비스의 소스가 현재 develop과 다름. 실제 배포 브랜치·커밋은 Render 관리 화면 접근 없이는 확정할 수 없음.
- `render.yaml` 설정은 `click-dev-api`, `develop`, 자동 배포 Off. 앱은 `click-db.onrender.com`을 사용함. 기존 서비스의 URL과 Blueprint 서비스가 같은 대상인지 반드시 확인 필요.
- `C:\click_server\Click`과 `C:\click_server\goal-work` 소스에는 `resolve_goal`, `respond_result`, `adjust_task`가 없음. 해당 FE/BE 작업 소스와 요청·응답 계약 확보 필요. 명령 이름만으로 호환 API를 추정 구현하지 않음.
- 사용자는 누락된 신규 명령을 BE에서 구현하도록 요청했다. 기존 계약 문서는 목표 분류·확정을 FE가 수행하고 start_task를 보내도록 정의한다. 신규 명령의 요청/응답 JSON 또는 FE 호출 코드 확보 후 새 계약을 구현해야 한다. goal 확인, 위험 action 승인, 결과 확인을 같은 확인 응답으로 처리하지 않는다.
- 이후 사용자가 BE에서 계약을 먼저 정의하고 FE가 따르도록 구현하는 방식을 요청했다. v0.3의 세 신규 명령을 로컬 구현했고, [FE 인계 계약](../../docs/agent-command-v03-fe-handoff.md)에 요청·응답과 처리 순서를 정리했다. 이 구현은 다른 PC의 FE 작업본과 맞춘 결과가 아니며, 해당 FE가 새 계약을 따르도록 변경되어야 한다. 결과 확인용 추가 마이그레이션 head는 `20261009_10`이다.

## 이번 로컬 수정

공통 요청 대기열의 claim이 생성한 version 0 세션에 start_task를 보내면 typed goal과 episode가 비어 있어 422가 발생했다. 기존 빈 세션에 목표·device context·episode를 초기화하도록 수정했다. 기존 버전 검사와 요청 재시도 보호는 그대로 적용한다.

로그인 후 `글자크기 키우고싶어` 입력을 enqueue하고 claim한 세션에 확인된 typed goal로 start_task를 보내 실제 runtime의 MVP-01 안내 및 후속 pause_task를 검증하는 회귀 테스트를 추가했다. 이 테스트는 현재 계약을 검증하며, 아직 확보하지 못한 resolve_goal 기반 목표 확인 UI나 실기기 동작을 검증하지 않는다.

관련 queue, typed contract, DB graph, 통합 테스트 32개 통과. 마이그레이션은 임시 SQLite DB에서 head `20261007_09`까지 적용하고 재적용 및 모델 컬럼 확인 통과. 원격 PostgreSQL 마이그레이션은 수행하지 않았다. 이번 수정은 기존 컬럼을 사용하므로 추가 DB 마이그레이션은 필요 없다.

## 기존 Render 서비스에 반영할 때

1. 앱이 쓰는 `click-db.onrender.com` 서비스의 연결 저장소가 `Bae-iri/Click`인지, 배포 브랜치가 develop인지, 현재 live commit이 무엇인지 확인한다. 신규 서비스를 만들기 전에 기존 서비스부터 확인한다.
2. 누락된 로컬 FE/BE 명령 계약 및 관련 마이그레이션을 검토·통합하고 이번 수정을 포함한 커밋을 원격 develop에 반영한다.
3. Dockerfile `./Backend/Dockerfile`, build context `.`, 시작 명령 `python scripts/render_start.py`, pre-deploy `python scripts/render_start.py --migrate`, health check `/ready`를 확인한다. 기존 DB의 DATABASE_URL을 유지하며 비밀값을 로그나 문서에 기록하지 않는다.
4. 해당 커밋을 수동 배포하고 pre-deploy의 `alembic upgrade head` 성공 및 `/ready`의 200 응답을 확인한다.
5. OpenAPI에 세 POST 경로와 FE가 사용하는 신규 명령들이 나타나는지 확인한다.
6. 로그인한 실기기에서 텍스트 입력 → enqueue → claim → 목표 확인 → 글자 크기 안내를 검증한다. 재시도, 후속 명령, 완료 후 다음 요청 처리도 확인한다.

텍스트와 음성은 같은 대기열을 사용한다. 이번 404는 음성 인식 실패가 아니며 FE 오류 문구 수정과 별도로 배포 문제를 해결해야 한다.
