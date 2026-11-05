# 백엔드 개발·운영 안내

갱신: 2026-11-03. 백엔드 문서는 이 파일과 자동 생성 [OpenAPI](openapi.json)로 관리합니다.

카카오 간편가입·로그인 설정과 기존 이메일 계정 호환은 [카카오 연결 설정](kakao-login.md)을 참고합니다. SMS 인증은 사용하지 않습니다.

## 폴더 구조와 책임

```text
Backend/
  app/
    api/v1/endpoints/  # HTTP 경계·인증 주체 전달
    schemas/           # 요청·응답 계약
    services/          # 목표 확인·작업 진행·안전 정책 조율
    repositories/      # 세션·graph·동의·이벤트·release DB 처리
    models/            # SQLAlchemy 테이블
    clients/           # AI1 adapter·checkpoint 변환·모델 서버 연결
    core/              # HTTP·보안·지표·검증용 진단
    task_graphs/       # 승인 seed·초안·입력 요건·graph loader
  migrations/          # DB schema 변경 이력
  scripts/             # 개발·운영 CLI; Agent2의 DB/AI1 연결 진입점
  inference/           # API와 별도로 실행하는 모델 worker
  tests/               # 백엔드 단위·통합 테스트
  contract_tests/      # 연동 계약 검사
  examples/            # 요청 예제
  docs/                # 이 안내와 OpenAPI
  instance/validation/ # 로컬 전용 DB·환경 설정·manifest·로그, Git 제외
```

Agent2 알고리즘은 `AI-Agent/src/ai_agent/graph_proposal/`에 둡니다. `scripts/agent2.py`는 DB 처리와 AI1 replay를 연결하며, Agent2 내부에서 Backend나 AI1 runtime을 직접 import하지 않습니다. 기존 CLI 경로는 유지합니다. 로컬 가상환경 `click/`, DB, 로그, 모델 가중치는 소스 구조와 구분하며 커밋하지 않습니다.

## 현재 상태

| 영역 | 구현 |
| --- | --- |
| 계정 | 인증·토큰 회전·비밀번호 변경/복구·이메일 인증 |
| 작업 | 텍스트/음성 대기열, 목표·필수 입력 확인, 결과 확인·추가 조절 |
| 세션 | DB/checkpoint 복구, version/lease 동시성, 재전송, 취소·만료·이력·피드백 |
| AI1 | 승인 graph 선택·고정·복원, 화면 안내·검증, 제한된 대체 graph 재선택 |
| AI2 | 동의 기반 수집·재구성·분석·데이터 분리·기존 경로 후보·평가·승인/롤백 |
| 운영 | 상태·지표·정리·백업/복구·컨테이너/Render 실행 도구 |

구조는 Android → FastAPI → AgentTurnService → DB graph 선택 → RuntimeAgentAdapter → AI1입니다. 결과·checkpoint·응답·실행 이벤트를 원자적으로 저장합니다. AI2는 사용자 응답과 별도 오프라인 명령으로 실행합니다. DB 컬럼은 [모델](../app/models), 변경 이력은 [migration](../migrations/versions)을 기준으로 봅니다.

## 실행

Python 3.11 이상에서 Backend 디렉터리를 기준으로 실행합니다.

```powershell
python -m pip install -e ".[test]"
.\scripts\dev.ps1
```

개발 스크립트는 DATABASE_URL 미설정 시 로컬 SQLite를 사용합니다. 직접 실행할 때는 대상 DB를 명시합니다.

```powershell
$env:DATABASE_URL = "sqlite+pysqlite:///./click-dev.db"
python -m alembic upgrade head
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload --no-access-log --no-proxy-headers
```

`/health`는 프로세스, `/ready`는 DB 연결과 필수 테이블/컬럼, `/docs`는 API 명세입니다. 신규 migration `20261027_14`는 graph release audit를 추가합니다.

## API

신규 연동은 `POST /api/v1/agent/commands`입니다. 목표/필수 입력 확인 → start_task → observe/행동 승인 → 결과 확인·추가 조절 순서입니다. `/agent/turn`은 raw_goal 호환 경로이고 `/sessions`는 기본 앱에서 비활성화됩니다. 정확한 필드·enum은 [OpenAPI](openapi.json), 앱 흐름은 [FE/BE 계약](../../docs/frontend-backend-contract.md)과 [목표·결과 흐름](../../docs/goal-progress-result-flow.md)을 따릅니다.

- 보호 API는 Bearer 인증과 본인 소유권을 검사합니다. 토큰은 opaque 난수이고 DB에는 해시를 저장합니다. access 기본 15분, refresh 최대 7일이며 비밀번호 변경/재설정 후 로그인 토큰을 폐기합니다.
- 새 요청은 새 request_id, 재전송은 같은 ID·본문·expected_version을 사용합니다. 후속 요청에는 session_id·episode_id·직전 session_version을 보냅니다.
- Tree·관측 ID·행동 이벤트는 실제 화면/행동을 반영해야 합니다. target_bounds는 화면 픽셀 좌표이며 Overlay 변환은 FE 책임입니다. 진행도는 탭 횟수가 아닙니다.
- 행동 승인은 사용자·세션·버전·화면·대상·행동에 결합하고 기본 120초 후 만료됩니다. confirmation_id와 새로 수집한 동일 화면이 필요합니다. 소비·만료·다른 화면 승인은 재사용하지 않습니다.
- 목표 확인, 위험 행동 승인, 결과 만족은 다른 명령입니다. 사용자 만족만으로 기술적 변경 성공을 확정하지 않습니다.

오류는 detail과 error(code/message/trace_id)를 사용하며 원문 입력·stack을 반환하지 않습니다. 409는 X-Conflict-Code에 따라 처리 중 재시도와 stale 상태 재수집을 구분하고, 410은 새 세션을 시작합니다. 401/403은 인증·소유권, 413/422는 크기·형식, 429는 제한, 502는 provider 응답 오류, 503/504는 미준비·실행 슬롯·timeout입니다. body 최대 1 MiB, Tree/vision 요소 각 최대 2,000개입니다.

## 개인정보

Jev 화면 선택을 사용할 때는 [휴대폰·백엔드의 화면 전송 필터와 ZDR 확인](jev-screen-privacy.md)을 적용합니다. 알 수 없는 화면 문구는 전송하지 않으며 민감·미지원 화면에서는 직접 진행하도록 안내합니다.

원본 목표·대화·Tree 전체·이미지·OCR 원문을 세션 DB에 저장하지 않습니다. 민감 입력·알려진 개인정보 패턴을 제거하고 외부 전송과 이벤트 metadata를 제한합니다. 모든 개인정보 탐지를 보장하지는 않습니다. 원본 이미지 외부 전송은 차단하며 Vision은 요청별 서버 검증 동의·기기 OCR·메뉴 allowlist를 사용합니다. user_consented=true만으로 허용하지 않습니다.

중립 응답을 저장하고 반환 시 최신 개인화 동의를 확인합니다. 개인화 철회는 이벤트·누적 프로필을 초기화합니다. AI2는 최신 model_training 동의 이후 생성된 미만료 이벤트만 처리하며 같은 사용자 잠금으로 철회와 직렬화합니다. 결과는 event receipt에 저장해 철회·탈퇴·세션 삭제·가장 오래된 근거 만료에 따라 삭제합니다. 기본 처리 명령은 사용자 데이터를 파일로 반출하지 않습니다.

별도 curated 파일·배포 모델은 DB cascade 대상이 아니므로 출처·동의·보존·폐기 정책이 필요합니다. 해시도 관리 대상이며 익명화·암호화를 보장하지 않습니다. 백업 복구 후 삭제 대상은 다시 삭제해야 합니다.

## 모델 연결

설정은 루트 `.env.example`과 [모델 worker 안내](../inference/README.md)를 따릅니다. 자체 STT/LLM worker는 API와 분리하고 실제 가중치·한국어 추론·메모리·지연을 확인합니다. STT worker와 API·앱 모델 계약은 Whisper large-v3로 전환했습니다. 실제 서버에는 CTranslate2 가중치 배치와 `inference.whisper_stt:app` 실행, API endpoint 변경 및 앱 업데이트가 필요합니다. 목표 해석 LLM도 STT와 별도 설정입니다.

```powershell
python scripts/check_stt.py --env-file .env
python scripts/check_stt.py --env-file .env --wav /path/to/test-ko.wav --expected-text "글자 크게 해줘"
```

CONFIGURED_ONLY는 설정 확인이며 추론 성공이 아닙니다. PASS는 실제 provider/model/전사 응답 검사이며 기대 문구를 지정하면 공백 제외 일치도 검사합니다. 공개 API 왕복은 `--api-url`과 STT_CHECK_ACCESS_TOKEN을 사용합니다(원격 HTTPS, 로컬 loopback만 HTTP 허용). CONFIG_ERROR/AUDIO_ERROR/NO_SPEECH/UNAVAILABLE/TIMEOUT/RESPONSE_ERROR/TEXT_MISMATCH를 구분합니다. Backend의 `422 NO_SPEECH`는 재녹음, `422 INVALID_REQUEST`는 입력 형식 오류이며 503 서버 장애와 다릅니다. 음성은 16kHz mono PCM16 WAV, 최대 20초/650000바이트이며 토큰·원음·전사문을 출력하지 않습니다.

2026-10-27 Windows 로컬에서는 Whisper large-v3 가중치·해시 확인, CPU/int8 모델 로드, worker와 API 상태 점검 및 인증을 확인했습니다. 관련 Backend 테스트 84개와 앱 오류 안내·모델 계약 단위 테스트 8개가 통과했습니다. 한국어 WAV가 없어 실제 전사·지연·전사 중 메모리 및 휴대폰 검증은 보류했습니다. 운영 배포는 별도입니다.

기기에서는 녹음 → 인증된 Backend → 실제 STT worker → 텍스트 표시와 장애 시 텍스트 입력 복구를 확인합니다. OCR도 동의 → 로컬 한국어 OCR → 마스킹된 elements → 실제 안내를 확인하고, 취소/화면 전환 이후 늦은 응답이 이전 작업을 실행하지 않는지 검사합니다. API 계약 확인만으로 모델·기기 성공을 기록하지 않습니다.

## 운영

세션 유휴 기본 1800초, lease 60초, AI timeout 20초이며 timeout은 lease보다 짧아야 합니다. SMTP 미설정 시 메일 요청은 503입니다. OPS_TOKEN은 32자 이상의 별도 난수이고 지표·인증 rate limit은 메모리 기반이어서 단일 worker를 유지합니다.

배포는 루트 Compose/render.yaml과 기존 DB를 사용합니다. Dockerfile은 Backend/Dockerfile, build context는 저장소 루트, 시작은 `python scripts/render_start.py`, pre-deploy는 `python scripts/render_start.py --migrate`, health check는 `/ready`입니다. 앱 API 주소·배포 branch/commit·서버 OpenAPI를 함께 확인합니다. 비밀값은 Git·로그·채팅에 넣지 않습니다.

글자 크기 경로 수정은 MVP-01/02의 `1.0.1` release(우선순위 101)에 포함됩니다. 시작 시 release 목록의 버전을 그대로 seed하며 기존 `1.0.0` 행은 변경하지 않습니다. 새 요청은 디스플레이 경로를 사용하고, 진행 중인 세션은 기존에 고정한 graph를 유지하므로 재배포 후 새 요청으로 검증합니다. 최대·최소값에서는 슬라이더 위치와 한계를 먼저 안내하며 결과 질문으로 자동 전환하지 않습니다.

DATABASE_URL을 설정한 환경에서 실행합니다.

```powershell
python scripts/agent2.py process --limit 100
python scripts/agent2.py audit
python scripts/maintenance.py
python scripts/monitor_check.py
```

AI2 상세 계약·합성 예제·승인/롤백은 [AI2 실행 안내](../../AI-Agent/src/ai_agent/graph_proposal/README.md)를 참고합니다. maintenance는 UI/종료 세션/runtime 이벤트·AI2 결과·대기열·만료 인증/승인을 정리합니다. DATA_RETENTION_SECONDS 기본 86400은 개발용 UI 이벤트·종료 기록 보존값이고 runtime TTL은 7일입니다. 주기 실행·알림은 외부 스케줄러에 연결합니다.

백업은 `python scripts/database_backup.py backup <새-파일>`, SQLite 복구는 같은 스크립트의 `restore-sqlite <백업> --new-database <새-DB>`를 사용합니다. PostgreSQL은 별도 빈 DB의 RESTORE_DATABASE_URL과 `python scripts/restore_postgres.py <백업>`을 사용합니다. 원본 DB를 덮어쓰지 않으며 복구 후 readiness·앱 흐름을 검사합니다.

## 중단 작업 정리와 기기 검증 준비

2026-10-27 중단 당시 변경은 검증용 서버/APK 환경 식별·민감 원문 없는 실행 진단, Agent2 평가 상태 보존·작업별 holdout 연결·graph audit 보완입니다. 백엔드 준비 코드는 아래 CLI로 연결합니다. 실제 휴대폰 실행·한국어 STT·제품 정확도 검증은 아직 완료로 기록하지 않습니다. APK 빌드와 설치는 별도 Frontend 작업입니다.

Backend 디렉터리에서 의존성을 설치한 Python으로 실행합니다. 아래 예제의 `python`은 같은 가상환경을 가리켜야 합니다.

```powershell
python scripts/validation.py prepare --env-file .env --port 8001
python scripts/supervise.py run api --python <가상환경-python.exe> --port 8001
# 다른 터미널에서 준비한 manifest와 실행 서버를 비교
python scripts/validation.py check
# 검증 세션의 구조화 진단만 조회; 독립 정답 데이터가 아님
python scripts/validation.py trace --session <session_id>
# 해당 supervisor 실행에만 중지 요청
python scripts/supervise.py stop api
```

prepare는 운영 DATABASE_URL 대신 `instance/validation/validation.db`를 사용하고 migration·승인 seed를 준비합니다. 같은 폴더의 `.env`에는 비밀값이 있으므로 공유하지 않습니다. 서버의 `/validation/environment`는 환경 ID를 설정한 경우에만 활성화하며 코드·migration·승인 graph hash를 비교합니다. 코드나 승인 graph가 달라지면 prepare·서버 재시작·APK 재빌드로 다시 맞춥니다. 검증 서버는 loopback에서 실행합니다.

`supervise.py run stt --python <STT-가상환경-python.exe>`는 별도 STT worker를 실행하며 기본 환경 파일은 `inference/.env`, 포트는 8091입니다. 모델 가중치와 worker 설정이 먼저 필요합니다. 감독 프로세스는 직접 시작한 자식만 재시작하며 이미 점유된 포트의 프로세스를 종료하지 않습니다. 터미널에서 실행하는 명령이므로 실행 중에는 터미널을 유지합니다.

## 검증과 남은 작업

```powershell
python -m pytest tests ../AI-Agent/tests -q
python scripts/smoke_check.py
python scripts/load_check.py
python scripts/export_openapi.py
```

2026-10-27 로컬 검증: 전체 1,020개 테스트, SQLite migration 왕복·schema check, 실제 서버 재시작·재전송·취소가 통과했습니다. 테스트 AI 부하는 동시 4개·총 40개가 성공했습니다. 합성 AI2는 episode 4개에서 후보 1개를 만들고 독립 기기 holdout 부재로 NEEDS_DATA를 반환했습니다.

2026-10-28 중단 작업 재개 검증: 기존 프로젝트 가상환경(Python 3.13.9)에서 전체 회귀 1,156개, 최종 Agent2·검증 환경 대상 41개, 원격 통합의 안내 방향 검사 5개가 각각 통과했습니다. migration 왕복·schema check와 실제 서버 재시작·재전송·복구·취소 smoke도 통과했습니다. 임시 검증 DB 준비 → supervisor API 실행 → 환경 fingerprint 일치 → 해당 run 종료를 확인했습니다. 합성 AI2는 episode 4개·후보 1개·NEEDS_DATA였으며 기기 성공률 검증은 아닙니다. 기본 시스템 Python은 numpy가 없어 테스트 수집에 실패했으므로 프로젝트 가상환경을 사용합니다. 기존 Starlette TestClient·Alembic 설정의 deprecation warning은 남아 있습니다.

로컬 seed release 89개 중 승인 2개는 구조 검사를 통과했습니다. 초안 81개는 미지원 완료 조건 등 추가 작업이 필요합니다. 실제 기기 작업별 정확도·완료·복구, 독립 gold/holdout, 실제 STT/LLM 품질·지연, PostgreSQL·실메일·운영 성능·복구 검증은 남아 있습니다. TEST_POSTGRES_URL을 설정한 `scripts/postgres_check.py`는 임시 schema에서 migration/HTTP를 검증합니다. 합성·테스트 AI 결과를 제품 성능으로 보고하지 않습니다.
