# 개발 서버 배포안

3주 차 업데이트: 신규 turn은 DB 영속화 완료. 아래 메모리 세션 제약은 레거시 `/sessions`와 인증 rate limit에 해당하며 single worker 설정은 유지한다. 실제 클라우드 생성·결제·배포는 팀 결정 대기 상태다.

사용자 요구: 기존 클라우드 계정·서버 없음. 팀원과 실제 Android 앱이 외부에서 접속해야 함.

## 제안 구성

- 공용 개발 API: Render Docker Web Service, Singapore, 소형 유료 0.5 CPU/512 MB.
- DB: 같은 region의 Render Postgres 17, 소형 유료 0.1 CPU/256 MB.
- 연결: Render HTTPS 주소 → API → 내부 DB URL. DB의 외부 IP 허용 목록은 빈 목록.
- 개발 branch: develop. 인스턴스 1개, 자동 배포 해제, 수동 배포로 시작.
- 외부 AI 키는 기본 미설정. 팀 테스트용 합성 데이터로 시작한다.
- 로컬 검증은 기존 Docker Compose 구성 유지. 현재 PC에는 Docker와 WSL 런타임이 설치되어 있지 않아 아직 실행하지 못했다.

구체적인 생성 설정은 루트 render.yaml에 작성했다. 아직 Render 리소스를 생성하거나 결제하지 않았다.

## 비용과 선택 이유

Render 공식 비용 안내의 2026년 7월 예시는 소형 Web Service + Postgres를 월 약 $13(추가 트래픽·스토리지 별도)로 설명한다. 이는 예산 참고치이며 지금 계정에 적용되는 견적이 아니다. 실제 Blueprint 생성 화면에서 컴퓨트·스토리지·워크스페이스·세금 등을 확인하고 예산 승인 후 생성한다.

무료 DB는 생성 후 30일 만료되므로 1~2개월의 지속 개발용으로 선택하지 않았다. 관리형 서비스를 쓰면 서버 OS와 HTTPS 구성 부담을 줄이고 백엔드 개발에 집중할 수 있다. 이 소형 구성의 실제 동시 요청 처리 성능은 부하 검증 전이다.

## 진행 순서

1. 사용자 Render 계정 생성, GitHub Bae-iri/Click 접근 연결.
2. 이번 서버 코드·배포 설정이 develop에 올라간 후 Blueprint에서 render.yaml 선택.
3. 생성될 API·DB의 지역·요금·스토리지 최종 확인과 사용자 승인.
4. 리소스 생성 → preDeploy migration → /ready 통과 확인.
5. 인증·본인 접근·토큰 갱신·재시작 유지 검증. 별도 임시 DB/schema에서 PostgreSQL 검증 스크립트 실행.
6. 실제 발급된 HTTPS 주소를 Android에 설정하고 로그인·토큰 헤더 연동.

비밀번호나 API 키를 채팅에 붙여넣을 필요는 없다. 계정의 로그인·결제·GitHub 연결은 사용자가 서비스 화면에서 수행한다.

Render 연결 문자열의 postgresql:// 형식은 render_start.py에서 설치된 psycopg3 드라이버 URL로 변환한다. API 포트는 PORT 환경변수를 사용한다. DB URL이 없으면 메모리 SQLite로 대체하지 않고 기동을 거절한다.

## 개발 단계 제약

안내 세션·rate limit은 프로세스 메모리이므로 배포/재시작 때 진행 중 안내는 초기화된다. rate limit은 프록시 아래에서 팀 전체를 같은 출발지로 볼 수 있으므로 본격적인 외부 사용 전 신뢰 프록시와 분산 제한을 구현한다. 개인정보 전체 마스킹·Android 인증 연결은 별도 미완료 작업이다. 공용 개발 API를 운영 서비스 완료로 취급하지 않는다.

## 근거

- [Render Blueprint 명세](https://render.com/docs/blueprint-spec)
- [무료 서비스·DB 제한](https://render.com/docs/free)
- [공식 비용 예시](https://render.com/articles/how-much-does-cloud-application-hosting-cost-for-small-businesses)
- [최종 요금 확인](https://render.com/pricing)
