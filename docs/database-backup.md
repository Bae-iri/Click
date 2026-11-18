# 운영 DB 백업과 복원

2026-11-18 작성. **운영 적용 전**: 현재 작업 환경에는 Render API 권한, 운영 PostgreSQL 연결, AWS 계정 자격 증명이 없다. 아래 파일은 배포 가능한 설정이며, 실제 버킷 생성·운영 백업·운영 데이터 복원 성공을 뜻하지 않는다. 로컬 `.env`는 SQLite 개발 DB이다. 기존 웹 서비스와 DB 설정은 변경하지 않는다.

## 구성

| 항목 | 설정 |
|---|---|
| 저장 위치 | `s3://<새 전용 버킷>/click/postgres/production/YYYY/MM/DD/<UTC시각>-<UUID>.dump` |
| 지역 | AWS `ap-southeast-1`, Render `singapore` (기존 DB와 같은 지역) |
| 백업 | 매일 한국 시간 03:00 (`0 18 * * *`, UTC) |
| 삭제 | 364일 경과 시 매시간 30분에 직접 삭제 + S3 364일 수명 주기 |
| 접근 제한 | 공개 접근 차단 4개, ACL 비활성화, HTTPS 강제, 전용 IAM 사용자/접두사 권한 |
| 저장 암호화 | S3 AES256 서버 측 암호화, 앱의 암호화 데이터는 암호문 상태로 보관 |
| 버전/복제 | 새 비버전 버킷, 복제 및 Object Lock 미설정; 오래된 버전이 남는 버킷은 작업 거절 |
| 복원 검증 | 매일 S3에서 다운로드 → SHA-256 대조 → 일회용 PostgreSQL 18 별도 서버에 복원 → 원본 스냅샷의 모든 사용자 테이블·행 수 대조 |
| 결과 | 같은 경로의 `.dump.json` 및 Render 실행 로그. 검증 실패 시 종료 코드 1 |

백업 저장은 Render 컨테이너의 임시 디스크와 독립적이다. 원본 DB는 읽기 전용 트랜잭션으로 일관된 스냅샷을 내보내고, 동일 스냅샷으로 `pg_dump`와 행 수를 구한다. 앱 실행·마이그레이션·운영 DB 쓰기는 하지 않는다. 복원 대상 URL을 운영자가 입력하는 대신 전용 컨테이너 내부에 새 서버를 생성하므로 운영 DB를 덮어쓸 수 없다. Linux에서는 TCP를 열지 않고 권한 0700의 Unix 소켓만 사용한다. 테스트 서버와 내려받은 파일은 작업 종료 시 폐기된다.

S3 수명 주기 삭제는 비동기여서 정확히 365일 시각에 물리 삭제를 보장하지 않는다. 이에 1일 여유를 두고 364일부터 명시적 삭제를 매시간 실행한다. **삭제 작업 장애나 AWS 장애까지 포함한 절대적인 365일 상한 보장은 아니다.** 삭제 실패는 비정상 종료로 보고되므로 Render 실패 알림을 활성화하고 24시간 이내 처리한다. 날짜가 파일명에 포함되며, 삭제 판단은 S3 생성 시각(`LastModified`)을 기준으로 한다. UUID 키는 덮어쓰지 않는다. 미완료 멀티파트 업로드는 1일 후 정리한다.

## 적용 순서

1. AWS 관리자 권한으로 새 전용 버킷을 생성한다. 기존 버킷을 가져오거나 다른 데이터가 있는 버킷을 사용하지 않는다.

   ```sh
   terraform -chdir=infra/backup init
   terraform -chdir=infra/backup plan -var='bucket_name=<고유한-백업-버킷명>' -out=backup.tfplan
   terraform -chdir=infra/backup apply backup.tfplan
   terraform -chdir=infra/backup output
   ```

   state/plan은 비공개 저장하고 Git에 넣지 않는다. `prevent_destroy`로 버킷의 실수 삭제를 막는다. 코드에는 AWS 액세스 키 생성 리소스가 없어 비밀키를 state로 출력하지 않는다.

2. 생성된 `click-db-backup` IAM 사용자의 액세스 키를 AWS 콘솔에서 발급한다. 이 사용자는 해당 버킷 보안 설정 조회와 지정된 백업 접두사의 읽기/쓰기/삭제만 허용한다. 버킷·IAM 설정 변경 권한은 없다.

3. Render에 **먼저** `click-db-backup-secrets` 환경 그룹을 만들고 다음 값을 비공개 환경 변수로 저장한다.

   | 변수 | 값 |
   |---|---|
   | `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | 전용 IAM 사용자 키 |
   | `AWS_DEFAULT_REGION` | `ap-southeast-1` |
   | `BACKUP_S3_BUCKET` | Terraform으로 생성한 실제 버킷명 |
   | `BACKUP_S3_EXPECTED_OWNER` | Terraform output의 12자리 AWS 계정 번호 |
   | `BACKUP_S3_PREFIX` | `click/postgres/production/` |

   Render 환경 그룹은 `sync: false`를 지원하지 않으므로 비밀값을 YAML에 적지 않고 수동으로 등록한다. `DATABASE_URL`, `DATA_ENCRYPTION_KEYS`, `DATA_LOOKUP_KEY`는 이 공통 그룹에 넣지 않는다. 삭제 전용 Cron Job에는 DB 접속 정보를 주지 않는다.

4. 저장소의 **`render-backup.yaml`** 을 지정하여 별도 Blueprint를 만든다. 기본 `render.yaml` 웹 서비스를 교체하지 않는다. 백업 Job의 `DATABASE_URL`에 기존 `click_db`의 직접 연결 Internal Database URL을 넣는다(PgBouncer URL 불가). 가능한 경우 전체 대상 스키마·테이블·시퀀스 읽기 권한이 있는 백업 전용 계정을 사용한다. `pg_dump`가 읽을 수 없는 데이터나 RLS 오류가 있으면 작업이 실패해야 하며 이를 무시하지 않는다. PostgreSQL 서버 메이저 버전이 18인지 확인한다.

5. `click-db-daily-backup`의 **Trigger Run**을 실행하고 결과 JSON의 `status=passed`, `restore.status=passed`, 저장 경로·SHA-256·테이블/행 수를 확인한다. `.dump`와 `.dump.json`이 비공개 버킷에 있는지 확인한다. 공개 URL은 생성하지 않는다. `click-db-backup-retention`도 수동 실행하여 `status=passed`를 확인한다. 두 Job의 실행 실패 알림을 담당자에게 설정한다. S3와 Render Cron Job 비용이 발생한다.

6. 운영 적용 기록에 버킷명, 두 Cron Job ID, 최초 성공 시각, 복원 테이블·행 수, Render DB의 플랜·복구 기간을 기록한다. 실패한 복원은 성공으로 기록하지 않는다.

## 복구와 암호화 키

백업 복원 검증은 DB 스키마·데이터의 복구를 확인한다. 애플리케이션 암호문을 해독하려면 당시의 `DATA_ENCRYPTION_KEYS`와 `DATA_LOOKUP_KEY`가 필요하다. 이 키는 덤프나 로그에 넣지 말고 별도 비밀 관리 저장소에 백업 보관 기간 동안 보관한다. 키를 잃으면 DB 파일만으로 앱 데이터 복구가 불가능하다.

실제 사고 복구는 `Backend/scripts/restore_postgres.py`로 **새 빈 DB**에 복원하고 앱의 암호화 키, readiness, 수용 테스트를 확인한 뒤 연결을 전환한다. 이 문서의 자동 일회용 검증은 운영 연결을 전환하지 않는다.

## Render 자체 백업

[Render 공식 문서](https://render.com/docs/postgresql-backups)에 따르면 유료 Render Postgres의 PITR 복구 창은 워크스페이스 Hobby 3일, Pro 이상 7일이다. 논리 백업(export)은 생성 후 7일 보관한다. Free compute DB에는 자동 논리 백업이 없다. 이는 2026-11-18 문서 기준이며, 현재 `click_db`의 실제 요금제·백업 활성화 여부·가장 오래된 복구 가능 시각은 **계정 접근 전이므로 미확인**이다. Dashboard → `click_db` → Recovery에서 확인하고 위 적용 기록에 남긴다. Render 자체 보관 기간은 S3 장기 백업과 별개다.

## 로컬 검증

2026-11-18 검증 결과: 백업 안전성·실제 PostgreSQL 복원 테스트 **9개 통과**, 기존 SQLite 백업 회귀 검사 **1개 통과**. 실제 Click 스키마와 합성 데이터 **27개 테이블·6개 행**이 복원 후 일치했다. AWS 공급자 스키마 기준 `terraform validate` 및 Render 공식 Blueprint JSON Schema 검증도 통과했다. Docker 이미지 빌드는 이 환경에 Docker가 없어 미실행이다. 상세 기록: [검증 결과](reviews/2026-11-18-db-backup-validation.json).

테스트는 운영 환경 변수/DB를 사용하지 않고 임시 PostgreSQL 18 서버 2개와 Moto의 S3 모의 서비스를 사용한다. 실제 Click Alembic 스키마와 합성 데이터를 덤프·다운로드·복원한다. **실제 AWS 업로드나 운영 데이터 복원 검증은 아니다.**

```sh
python -m pip install -r Backend/backup-requirements.txt 'moto[s3]>=5,<6' pytest
cd Backend
# PostgreSQL 18 bin을 PATH에 추가한 뒤 실행. Linux에서는 root 이외 계정.
CLICK_BACKUP_POSTGRES_TEST=1 python -m pytest tests/test_managed_database_backup.py -q -s
```

참고: [Render Cron Jobs](https://render.com/docs/cronjobs), [Render → S3 백업](https://render.com/docs/backup-postgresql-to-s3), [Blueprint 비밀값 제약](https://render.com/docs/blueprint-spec#prompting-for-secret-values), [S3 삭제 지연과 고정 기한 제한](https://docs.aws.amazon.com/AmazonS3/latest/userguide/how-to-set-lifecycle-configuration-intro.html).
