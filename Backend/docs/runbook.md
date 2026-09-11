# 로컬 실행과 검증

## 설치

PowerShell에서 Python 3.11 이상을 사용한다. 프로젝트 안의 가상환경을 권장한다.

```powershell
cd C:\click_server\Click\Backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[test]"
.\scripts\dev.ps1 -Python .\.venv\Scripts\python.exe
```

이미 의존성이 설치된 Python이면 `./scripts/dev.ps1`만 실행한다. 실행 정책상 ps1 실행이 제한되면 아래 동등한 명령을 직접 실행한다.

```powershell
$env:DATABASE_URL = "sqlite+pysqlite:///./click-dev.db"
python -m alembic upgrade head
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

개발 스크립트는 DATABASE_URL이 없으면 로컬 SQLite 파일을 선택하고 migration을 실행한다. 이미 설정된 URL은 유지한다. 사용하려는 개발 DB인지 확인하고 실행한다. 로컬 주소에 바인딩하며 단일 worker를 사용한다.

환경변수를 전혀 주지 않고 `app.main:app`만 실행하면 기존 코드의 메모리 SQLite가 사용되어 재시작 시 데이터가 사라진다. 파일 DB에서도 **안내 세션·응답 캐시는 여전히 메모리**다.

## 환경 파일

`Backend/.env.example`은 로컬 SQLite 예시, 루트 `.env.example`은 PostgreSQL 예시다. `.env` 파일은 Python이 자동으로 읽지 않는다. 환경변수를 직접 지정하거나 uvicorn에 `--env-file .env`를 명시한다. Alembic은 현재 프로세스의 DATABASE_URL을 읽는다. 두 명령이 같은 DB를 사용하도록 맞춘다.

외부 AI 호출 없이 개발하려면 GEMINI_API_KEY를 비워둔다. 키를 설정하면 기존 runtime이 외부 호출을 사용할 수 있다. 시험 입력은 합성 데이터로 제한하고 실제 개인정보 전송 전에는 개인정보 파이프라인 backlog를 완료한다.

## 확인

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
Invoke-RestMethod http://127.0.0.1:8000/ready
$sample = [System.IO.File]::ReadAllBytes((Join-Path (Get-Location) "examples/agent-turn.json"))
Invoke-RestMethod http://127.0.0.1:8000/api/v1/agent/turn -Method Post -ContentType "application/json; charset=utf-8" -Body $sample
```

- `/health`: `{"status":"ok"}`
- `/ready`: `{"status":"ready","database":"ok"}`. 연결 불가·테이블 누락은 503.
- `/docs`: Swagger UI, `/openapi.json`: 현재 스키마.
- 샘플 turn은 기존 글자 크기 시범 runtime의 응답 확인용이며 실제 기기 연동 검증은 아니다.

## PostgreSQL

루트 docker-compose.yml의 PostgreSQL 17 정의를 사용한다. `.env`에 로컬 개발 비밀번호를 설정하고 다음을 실행한다. 기존 볼륨의 비밀번호는 .env 변경만으로 바뀌지 않는다.

```powershell
cd C:\click_server\Click
docker compose up -d postgres
cd Backend
$env:DATABASE_URL = "postgresql+psycopg://click:YOUR_DEV_PASSWORD@localhost:5432/click"
python -m alembic upgrade head
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

비밀번호는 URL 인코딩 규칙을 지켜 구성한다. 접속 문자열과 `.env`는 커밋하지 않는다. 실제 PostgreSQL 확인 여부는 검증 기록에 별도로 표시한다.

## 테스트와 문서 갱신

```powershell
cd C:\click_server\Click\Backend
python -m pytest -q
python scripts/export_openapi.py
python scripts/smoke_check.py
cd ..\AI-Agent
python -m pytest -q
```

smoke_check는 임시 SQLite DB에 migration을 적용하고 임시 포트로 실제 서버를 실행해 HTTP를 검증한 뒤 해당 프로세스와 임시 파일을 정리한다. 기존 DB와 서버를 사용하지 않는다. 외부 AI 키는 자식 프로세스에서 비운다.
