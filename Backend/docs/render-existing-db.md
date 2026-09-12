# 기존 Render DB에 백엔드 연결

사용자가 만든 DB: `click_db`, Singapore, PostgreSQL 18. 실제 연결·마이그레이션은 아직 실행하지 않았다. CI는 PostgreSQL 17·18 검증 행렬로 설정했으며 통과 여부는 실제 실행 결과를 확인한다.

`render.yaml`은 Web Service만 정의한다. DATABASE_URL은 Render에서 입력하며 기존 DB를 재사용한다. 별도 DB를 추가 생성하지 않는다. DB 표시 이름과 실제 database/user 이름은 다를 수 있으므로 연결 문자열은 대시보드에서 복사한다.

## Dashboard에서 Web Service 생성

- New → Web Service → GitHub `Bae-iri/Click` 선택.
- Name: `click-dev-api`, Branch: `develop`, Region: Singapore.
- Language/Runtime: Docker, Root Directory: 비움.
- Dockerfile Path: `./Backend/Dockerfile`, Docker Build Context: `.`.
- Instance: 기존 제안의 유료 0.5 CPU / 512 MB. 생성 화면의 월 요금 확인.
- Environment: `DATABASE_URL`에 click_db의 Internal Database URL 입력. GEMINI_API_KEY는 추가하지 않는다.
- Advanced → Docker Command: `python scripts/render_start.py`.
- Advanced → Pre-Deploy Command: `python scripts/render_start.py --migrate`.
- Health Check Path: `/ready`. Auto Deploy는 Off.

DB 주소는 비밀번호를 포함하므로 채팅·GitHub·스크린샷에 노출하지 않는다. 같은 Render 계정·지역의 서비스는 internal URL로 연결한다. 외부 DB 접근이 필요 없으면 DB Networking의 외부 접근 목록을 비운다.

생성은 Web Service에 별도 요금이 발생한다. 배포가 성공하면 제공된 HTTPS 주소의 `/health`, `/ready`, `/docs`를 확인한다. /ready가 실패하면 Logs에서 pre-deploy migration과 연결 오류를 확인한다. 비밀값은 가린다.

SMTP 발송·운영 지표는 초기 서버 연결 이후 `.env.example` 및 [인수 문서](backend-completion.md)를 따라 설정한다. /docs 노출·접근 정책, 모니터링·정리 주기·백업은 운영 인수 시 확정한다.
