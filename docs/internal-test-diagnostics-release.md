# 내부 테스트 진단 배포

앱의 내부 테스트 링크는 유지하고, 같은 앱·테스트 트랙에 새 AAB를 올린다.
이 변경은 기기 내 진단 파일과 수동 공유를 추가한다. 서버로 자동 전송하는 기능은 추가하지 않는다.

## 준비한 버전

- 패키지: `com.clueclick.app`
- 버전: `1.0.1`, `versionCode=2` (Play 최고 코드 확인 전의 준비값)
- 빌드: `release`, `DEBUG=false`, `CANDIDATE_DIAGNOSTICS=true`
- 서버: `https://click-db.onrender.com`
- 키: 기존 `/Users/galbaesaida/click_upload.jks`, 별칭 `click-upload`를 사용해야 함.

현재 Windows 작업 공간에서는 Mac 키를 읽을 수 없어 AAB는 서명 전 상태로 준비한다.
서버 배포 자격 증명도 없어 서버 변경은 아직 운영에 반영하지 않았다.
앱 자체의 수집·부모 연결·전송 필터 기록은 새 앱에서 남지만, 새 서버 후보별 집계는 서버 반영 후 나온다.

## Mac에서 기존 키로 서명

준비 폴더의 `click-internal-diagnostics-v2-unsigned.aab`와 `sign-play-bundle.sh`를 Mac의 같은 폴더로 옮긴다.
그 폴더의 터미널에서 실행한다. JDK의 `jarsigner`, `keytool`이 필요하다.

```bash
bash sign-play-bundle.sh click-internal-diagnostics-v2-unsigned.aab /Users/galbaesaida/click_upload.jks click-upload
```

키 비밀번호는 `jarsigner`가 물을 때 입력한다. 명령줄이나 채팅에 쓰지 않는다.
결과 파일은 `click-internal-diagnostics-v2-unsigned-signed.aab`다.
기존 `/Users/galbaesaida/Desktop/프로젝트/Click/Frontend/app/release/app-release.aab`가 있으면
스크립트가 두 파일의 서명 인증서 SHA-256을 비교하고, 다르면 오류로 종료한다.
기존 파일이 없으면 Play Console의 업로드 키 인증서와 직접 비교해야 한다.

Play 업로드 전 기존 앱의 패키지가 `com.clueclick.app`인지, 업로드된 최고 코드가 `2`보다 작은지 확인한다.
이미 `2` 이상이 올라갔다면 코드를 올려 다시 빌드해야 한다. AAB 파일명을 바꾸는 것으로 버전 코드는 바뀌지 않는다.

## 이후 같은 소스에서 빌드

`Frontend/release-signing.properties.example`을 `Frontend/release-signing.properties`로 복사하고
기존 키 경로·별칭·비밀번호를 로컬 파일에 채운다. 해당 파일과 키는 Git에서 제외된다.

```bash
# Mac, Frontend 디렉터리. versionCode는 실제 Play 최고 코드보다 크게 지정한다.
bash gradlew :app:bundleRelease -PCLICK_CANDIDATE_DIAGNOSTICS=true \
  -PCLICK_VERSION_CODE=2 -PCLICK_VERSION_NAME=1.0.1 -PCLICK_REQUIRE_RELEASE_SIGNING=true
```

서명 설정이 없으면 이 명령은 실패하므로 서명 안 된 파일을 업로드용으로 오인하지 않는다.
Windows에서는 같은 설정으로 다음 스크립트를 사용할 수 있다.

```powershell
./scripts/build-internal-test.ps1 -VersionCode 2 -VersionName '1.0.1'
```

## 서버 반영과 기기 재현

서버 변경은 `Backend/app/services/screen_jev.py`,
`AI-Agent/src/ai_agent/telemetry/trace_logger.py`, 관련 테스트다.
준비 폴더의 `server-diagnostics.patch`는 이 세 파일만 포함한다. 서버 소스의 동일 기준 버전에
`git apply --check`로 적용 가능 여부를 확인한 뒤 반영하고, 기존 배포 절차로 재배포한다.
현재 `render.yaml`은 `develop`의 검증 통과 후 자동 배포 설정이다.
이번 작업에서는 원격 푸시나 운영 배포를 실행하지 않았다.

Play Console의 **기존 앱 → 테스트 → 내부 테스트 → 새 버전 만들기**에서 서명한 AAB를 올린다.
참여자에게 Play 스토어에서 업데이트한 뒤 문제를 재현하도록 안내한다.
문제가 발생하면 **앱 메뉴 → 테스트 진단 자료 공유**에서 파일을 전달받는다.
파일 해석은 [설정 메뉴 후보 진단](settings-candidate-diagnostics.md)을 따른다.

기기 내 파일은 백업 제외 영역의 두 파일로 총 512 KiB 이내로 제한되며,
공유 시 앱 버전·기종·Android SDK 버전·관측 시각을 포함한다.
텍스트 원문·계정 정보·음성·화면 이미지·키 비밀번호는 기록하지 않는다.
일반 배포에서 `CLICK_CANDIDATE_DIAGNOSTICS`를 생략하면 기록과 공유 메뉴가 비활성화된다.

릴리스 자동 테스트에는 원본 구조·필터 전후·서버 진단 필드 제한·파일 순환 저장·메뉴 회귀 검사가 포함된다.
Windows에서는 FileProvider 설정 범위를 검사하고, Unix CI에서는 실제 공유 URI 접근 제한도 검사한다.
실제 폰에서의 공유·메뉴 재현과 Mac 업로드 키 서명은 별도로 확인해야 한다.
