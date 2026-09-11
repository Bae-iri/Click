# 2주 차 구현 결과와 인증 계약

## 상태

| 작업 | 상태 | 결과 |
|---|---|---|
| B01 인증 계약 | 구현 기준 결정 | 기존 앱의 이메일·비밀번호 입력 화면에 맞춘 자체 인증 |
| B02 인증 처리 | 구현·로컬 검증 완료 | 회원가입, 로그인, 갱신, 로그아웃, 비밀번호 변경 |
| B03 본인 접근 제어 | 구현·로컬 검증 완료 | 사용자·개인화 API와 turn/레거시 세션 보호 |
| B04 오류·입력 제한 | 구현·로컬 검증 완료 | 공통 오류·추적 ID, body 1 MiB, tree/vision 각 2,000개 제한 |
| B05 PostgreSQL·개발 배포 | 구성 완료, 실제 실행 미검증 | Dockerfile, Compose, PostgreSQL 검증 스크립트, GitHub Actions |

현재 PC에서 Docker·PostgreSQL 실행 도구를 찾지 못했고 원격 개발 서버 정보도 제공되지 않았다. 실제 PostgreSQL 검증·Docker 빌드·원격 배포가 완료된 것은 아니다. GitHub Actions도 아직 실행하지 않았다.

## 인증 API

| Method / Path | 입력 | 성공·주요 오류 |
|---|---|---|
| POST /api/v1/auth/register | email, password, 선택 age_group·occupation_category | 201 토큰·user_id, 중복 email 409 |
| POST /api/v1/auth/login | email, password | 200 토큰, 잘못된 계정·비밀번호 401 |
| POST /api/v1/auth/refresh | refresh_token | 200 새 access/refresh, 만료·재사용 401 |
| POST /api/v1/auth/logout | Authorization 헤더 | 204, 현재 로그인 세션의 토큰 폐기 |
| POST /api/v1/auth/password | 인증 헤더, current_password, new_password | 204, 모든 로그인 세션 폐기 후 재로그인 |
| GET /api/v1/users/me | Authorization 헤더 | 200 현재 사용자·프로필 |

비밀번호는 12~128자. 이메일은 공백 제거·소문자 정규화 후 ASCII 이메일 형식을 검사한다. 이메일의 실제 소유권을 검증하는 메일 인증 기능은 아직 없다. 기존 로컬 프로필과 서버 계정은 자동 연결하지 않는다.

등록·로그인 응답 예시:

```json
{
  "user_id": "UUID",
  "token_type": "bearer",
  "access_token": "opaque-random-token",
  "refresh_token": "another-opaque-random-token",
  "expires_in": 900,
  "refresh_expires_in": 604800
}
```

이 토큰은 JWT가 아닌 충분한 난수의 opaque token이다. DB에는 SHA-256 해시만 저장한다. access는 15분, refresh는 최초 발급부터 최대 7일이다. refresh 시 둘 다 교체하고 원래 refresh의 만료 시점은 연장하지 않는다. 동시 refresh는 DB compare-and-swap으로 한 요청만 성공한다. 새 응답의 refresh_expires_in은 남은 시간이다.

비밀번호는 각 계정별 랜덤 salt와 scrypt(N=32768,r=8,p=1)로 해시한다. 로그인에서 없는 이메일도 dummy hash를 검증한다. 회원가입 중 충돌하면 프로필·계정·토큰 생성 전체를 rollback한다.

## 기존 계약 변경

- `POST /api/v1/users` 공개 생성 API를 제거했다. 등록은 `/auth/register`를 사용한다.
- 사용자 API, `/agent/turn`, 레거시 `/sessions`에 `Authorization: Bearer <access_token>`이 필수다.
- user_id를 임의로 보내 타인의 개인화를 사용할 수 없다. turn에서 생략하면 인증 사용자 ID를 채운다.
- 다른 사용자의 사용자 API 접근은 403, 레거시 세션 접근은 404, turn 세션 접근은 403이다.
- 삭제된 계정의 토큰은 FK CASCADE로 함께 제거되어 다음 요청부터 401이다.
- 안내 세션 소유권은 현재 프로세스 메모리다. 단일 worker만 사용한다. 공유 저장소·TTL·확장 가능한 동시성은 3주 차 작업이다.
- 기존 안드로이드 Gateway는 토큰 헤더를 아직 보내지 않으므로 수정 전에는 401을 받는다. 인증을 끄는 우회 모드는 제공하지 않는다.

## Android 연동 순서

1. 가입 화면에서 register 호출 후 user_id·토큰을 수신한다. 기존 비밀번호 6자 검사를 서버 기준 12자로 맞춘다.
2. access token은 메모리 중심으로, refresh token은 Android 보안 저장소를 활용해 저장한다. LocalAccountStore의 평문 SharedPreferences에 비밀번호·토큰을 추가하지 않는다.
3. 모든 보호 API에 Bearer 헤더를 넣는다. 앱 실행 시 저장된 로컬 프로필만으로 로그인 완료 처리하지 않는다.
4. 401이면 refresh를 앱 내 단일 요청으로 직렬화하고 새 토큰 쌍을 함께 저장한다. 회전 실패·만료면 재로그인한다.
5. 로그아웃은 서버 호출 후 로컬 자격증명을 제거한다. 비밀번호 변경·탈퇴 후에도 제거한다.

이 변경은 서버 구현과 계약 문서에 적용했으며 Android 화면·토큰 저장·네트워크 코드는 아직 연결하지 않았다.

## 공통 오류와 요청 제한

```json
{
  "detail": "Authentication required.",
  "error": {
    "code": "UNAUTHENTICATED",
    "message": "Authentication required.",
    "trace_id": "server-generated-id"
  }
}
```

기존 detail 필드는 일반 메시지로 유지한다. validation 원문·DB 오류·stack은 반환하지 않는다. X-Request-ID는 서버에서 생성하며 응답과 로그에 같은 값을 사용한다. HTTP 로그는 추적 ID·상태·소요시간만 기록한다. 실행 스크립트는 URL·query를 남기는 uvicorn access log를 끈다. runtime 내부 debug/trace의 개인정보 제거는 여전히 후속 작업이다.

body는 JSON 파싱 전에 총 1 MiB로 제한한다. Content-Length가 없거나 작게 선언돼도 실제 수신량을 검사한다. 413은 body 초과, 422는 스키마 오류, 429는 인증 시도 제한이다. Vision 원본 base64 역시 전체 body 한도에 포함되어 큰 이미지는 거절된다.

register/login/refresh/password는 원격 IP별 합산 분당 20회다. 현재 개발용 단일 프로세스 제한이며 forwarded IP 헤더를 신뢰하지 않는다. 프록시 배포 시 신뢰할 프록시·분산 rate limit을 설정해야 한다.

## DB 및 배포

새 migration `20260911_02`: accounts(user_id PK/FK, email UNIQUE, password_hash), auth_sessions(id, user_id FK/index, access_hash UNIQUE, refresh_hash UNIQUE, 만료 시각).

기존 users는 보존하지만 이메일·비밀번호가 없어 자동 계정 생성하지 않는다. 나중에 기존 프로필을 연결하려면 별도 소유권 확인 절차가 필요하다. PostgreSQL의 기존 ui_events.metadata JSON도 모델과 맞게 JSONB로 변환한다.

```powershell
cd C:\click_server\Click
# 루트 .env에 URL에 안전한 개발용 POSTGRES_PASSWORD 설정
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build -d
```

PostgreSQL healthy → 일회성 migration 성공 → backend 실행 순서다. DB와 API 포트는 로컬 주소에만 공개한다. 외부 AI 키는 기본 비어 있다. 외부 공개 서버는 HTTPS·프록시·비밀값 관리·개인정보 파이프라인을 준비한 뒤 적용한다.

실제 PostgreSQL 테스트(기존 public 스키마를 변경하지 않고 UUID 임시 스키마를 생성·삭제):

```powershell
cd C:\click_server\Click\Backend
$env:TEST_POSTGRES_URL = "postgresql+psycopg://click:YOUR_DEV_PASSWORD@localhost:5432/click"
python scripts/postgres_check.py
```

GitHub Actions는 별도의 PostgreSQL 17 서비스로 같은 검증을 수행하도록 구성했다. 실제 실행 결과가 나오기 전에는 통과로 기록하지 않는다.

## 남은 범위

실제 PostgreSQL/컨테이너 검증·원격 배포, Android 로그인 연결, 이메일 인증·분실 비밀번호 복구, 세션 영속화·분산 rate limit·개인정보 전체 파이프라인은 미완료다. 이번 서버 인증은 개발 단계의 기능 완료이며 전체 서비스의 운영 준비 완료를 뜻하지 않는다.
