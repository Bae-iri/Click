# 서버와 APK를 같은 실행 환경으로 고정하기

개발 검증은 `Backend/instance/development` 한 세트를 사용한다. 운영 DB를 복사하거나 열지 않는다.
원본 `Backend/.env`는 자격증명의 입력으로만 사용하고, 개발 DB 주소와 암호화 키는 별도로 생성·보존한다.

Backend 의존성을 설치한 Python을 사용한다. Windows에서는 `python -m pip install -e '.[test]'`로
`psycopg[binary]`를 포함한 저장소 의존성을 설치한다. AI-Agent 소스 경로는 아래와 같이 추가한다.

```powershell
cd C:\click_server\Click\Backend
$env:PYTHONPATH='C:\click_server\Click\AI-Agent\src'
$env:PYTHONUTF8='1'
python scripts/validation.py --instance instance/development prepare --port 8120
python scripts/validation.py --instance instance/development serve
```

준비 명령은 명시하지 않은 화면 판단·목표 해석 provider를 모두 `jev`로 고정한다.
`TYPESAFE_API_KEY`가 필요하다. 원본 `.env`에서 provider를 명시한 경우 그 선택을 보존하며 자동 대체하지 않는다.
Gemini 실험 모델은 `gemini-3.8-flash`로 유지한다. provider 설정, 모델, 실제 소스 해시,
Git 커밋, DB migration과 승인 그래프를 `environment.json`의 fingerprint로 묶는다.

SQLite DB와 암호화 키는 `instance/development`에서 재사용한다. 이 폴더의 `.env`를 지우거나
키를 새로 생성해서 기존 DB를 열지 않는다. 사용 중인 원본 `.env`만 지정해 서버를 직접 시작하면
provider와 영구 DB가 빠질 수 있으므로 위 명령을 사용한다.

다른 터미널에서 같은 Python과 환경으로 검증 APK를 빌드한다.

```powershell
cd C:\click_server\Click\Frontend
.\scripts\validation.ps1 -BackendPython 'C:\Python314\python.exe' -Instance development
```

`BackendPython`은 실제 의존성을 설치한 Python 경로로 바꾼다.
이 스크립트는 서버 fingerprint를 검사한 뒤 해당 manifest의 API 주소를 넣어 APK를 생성하고,
APK 해시와 환경 manifest를 함께 보관한다. 기본 운영 APK와 별도 패키지로 설치된다.
USB 기기가 연결된 경우 `-Install -Adb 'C:\click_server\.android-sdk\platform-tools\adb.exe'`를 추가하면
8120 포트 전달과 설치를 진행한다. 접근성 서비스는 시험할 앱 하나만 켠다.

코드를 수정하거나 pull한 뒤에는 prepare → 서버 재시작 → APK 빌드를 같은 순서로 다시 진행한다.
fingerprint가 다른 서버에는 검증 APK가 요청을 보내지 않는다. 운영 서버 배포와 실제 기기 검증은 별도로 기록한다.

## 통신·관측·인증 제한

* 일반 에이전트 요청: 큐 대기를 포함한 전체 35초.
* 정부24 공개 안내: 계약 조회·인증 갱신·실제 요청을 포함한 전체 90초, 개별 공개 안내 읽기 최대 45초.
  연결 감시기는 이 경로의 읽기 시간과 남은 전체 예산을 함께 적용한다. 인증 후 재요청도 예산을 연장하지 않는다.
* 갱신 토큰을 401로 거절받았을 때만 로그인 정보를 삭제한다. 429·5xx·연결 실패에서는 토큰과 요청 저널을 유지한다.
* 접근성 수집은 모든 연관 탐색을 합쳐 최대 2,000회 방문·64단계·150ms로 제한한다.
  한도에 도달하면 부분 트리를 폐기한다. 개별 Android 시스템 호출이 반환하기 전의 지연까지 강제로 중단하는 제한은 아니다.
* 비밀번호 로그인은 IP 제한에 더해 계정별 5분당 10회 제한을 DB에 저장한다.
  서버 재시작·여러 worker·클라이언트 IP 변경으로 해당 계정 제한을 초기화하지 않는다.
  새 migration `20261119_21`을 적용한 뒤 서버를 배포한다. 기존 4자리 비밀번호 계약은 유지한다.
* release는 HTTP를 허용하지 않는다. debug/검증 APK만 로컬 HTTP를 허용한다.
  인증 토큰과 그 저장소의 요청 저널은 클라우드 백업과 기기 전송에서 제외한다.

## 검증

```powershell
cd C:\click_server\Click\Backend
python -m pytest -q
cd ..\AI-Agent
python -m pytest -q
cd ..\Frontend
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lint --continue --no-daemon
```

Robolectric 기본 SDK는 지원되는 35로 고정하며, 기기 전용 OAuth SDK 초기화는 테스트 shadow로 격리한다.
특정 API 버전이나 Application이 필요한 테스트는 자신의 `@Config`를 우선한다.
테스트용 Android 의존성 다운로드·로컬 TCP 테스트에 대한 실행 환경의 통신 제한은 제품 실패와 구분한다.
대규모 Backend 검증은 `python -m pytest -n 4 --dist=loadfile -q`로 파일별로 격리해 병렬 실행할 수 있다.
Windows에서 기본 임시 폴더에 접근할 수 없으면 `--basetemp`로 작업공간 안의 **새 전용 테스트 폴더**를 지정한다.
pytest는 기존 basetemp 내용을 지울 수 있으므로 DB·키·APK 보관 폴더를 지정하지 않는다.

자동 테스트와 APK 빌드만으로 실기기 완료를 선언하지 않는다. 설정 변경, 정부24 인증 인계·복귀,
오프라인·느린 응답·429/503·취소 후 재시도를 같은 APK/서버 fingerprint에서 반복한다.
