# 첫 주 산출물 검증 기록

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
