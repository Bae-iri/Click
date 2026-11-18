# 진단용 내부 테스트 v3

- 앱 ID: `com.clueclick.app`
- 버전 이름: `1.0.2`
- `versionCode`: **3**
- `compileSdk`: **36**
- `targetSdk`: **36**
- `minSdk`: 26 유지
- AGP 8.10.1 / Gradle 8.11.1 / JDK 17
- `DEBUG=false`, `CANDIDATE_DIAGNOSTICS=true`
- AAB는 **미서명**. 기존 Play 업로드 키로 서명한 뒤 기존 내부 테스트 트랙에 업로드한다.

## 빌드

```bash
cd Frontend
bash gradlew :app:bundleRelease -PCLICK_CANDIDATE_DIAGNOSTICS=true \
  -PCLICK_VERSION_CODE=3 -PCLICK_VERSION_NAME=1.0.2
```

이 미서명 산출물을 만들 때는 `release-signing.properties`나 `CLICK_RELEASE_SIGNING_FILE`을 제공하지 않았다.
친구에게 전달할 파일명은 `click-internal-diagnostics-v3-unsigned.aab`다.
준비 폴더에 있는 서명 스크립트를 Mac에서 실행한다.

```bash
bash sign-play-bundle.sh click-internal-diagnostics-v3-unsigned.aab /Users/galbaesaida/click_upload.jks click-upload
```

키 비밀번호는 jarsigner가 물을 때 입력한다. 기존 키를 새로 생성하거나 교체하지 않는다.
출력은 `click-internal-diagnostics-v3-unsigned-signed.aab`다.
현재 준비 파일의 SDK·버전·진단 플래그·서명 유무·SHA-256은 `build-verification.json`에서 확인한다.

## 정부24 변경

[정부24 작업 그래프 생성과 AI-Agent2 경로 개선](gov24-task-graph-learning.md)을 함께 반영한다.
정부24의 공개 메뉴 이동에서 실제로 검증된 등본 안내 경로를 그래프로 생성·저장하고,
성과 기준을 충족한 경로를 새 안내의 후보로 사용한다. `주민등록등본 발급해줘`처럼 앱 이름을 생략한 요청도 처리한다.
지원 범위는 발급 안내 화면 도착까지이며, 실제 발급 신청·인증 완료를 자동 달성했다고 표시하지 않는다.
다른 민원을 전부 새로 지원한다는 의미는 아니다.

서버 변경 커밋을 Render에 반영해야 요청 해석과 AI-Agent2 경로 개선이 적용된다.
기존 v2 파일은 이 SDK 36 변경을 포함하지 않는다. Play에서 v3 업데이트 후 같은 상황을 재현하고
앱 메뉴의 **테스트 진단 자료 공유**로 파일을 전달한다.

합성·공유 DB·Android 자동 테스트는 실제 휴대폰에서의 최종 발급 성공을 의미하지 않는다.
실제 기기의 정부24 화면 경로와 Play 업로드 결과는 업데이트 후 확인해야 한다.
