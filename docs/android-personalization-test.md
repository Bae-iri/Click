# Android 사용자별 맞춤 안내 테스트

## USB로 PC 서버 연결

Android Studio에서 Frontend를 열어 Android SDK를 설치한다. 휴대폰에서 개발자 옵션과 USB 디버깅을 켜고 PC 연결을 허용한다. SDK platform-tools의 adb를 PATH에 추가하거나 아래 스크립트에 -Adb로 adb.exe 경로를 전달한다.

첫 번째 PowerShell에서 저장소 루트를 기준으로 실행한다. 기본 DB는 재시작해도 유지되는 Backend/click-dev.db이며 기존 DATABASE_URL이 있으면 그 DB를 사용한다.

```powershell
cd Backend
.\scripts\dev.ps1
```

두 번째 PowerShell에서 실행한다. 스크립트는 서버 준비 상태를 확인하고, USB 포트 연결 후 앱을 빌드·설치·실행한다. USB를 다시 연결하면 포트 연결도 다시 실행해야 한다.

```powershell
cd Frontend
.\scripts\install-usb.ps1
# adb가 PATH에 없다면:
# .\scripts\install-usb.ps1 -Adb 'C:\path\to\Android\Sdk\platform-tools\adb.exe'
```

여러 기기가 연결돼 있으면 -Serial로 테스트 기기를 지정한다. SDK 경로는 Android Studio 또는 커밋하지 않는 Frontend/local.properties의 sdk.dir에 설정한다. 이 빌드는 USB 연결 중에 PC 서버를 사용하는 테스트 APK다.

## 사용자 테스트

1. 회원가입 또는 로그인 후 메뉴의 '맞춤 안내 기록'에 동의한다.
2. 다른 앱 위에 표시, 접근성, 마이크 권한을 설정한다.
3. '글자 크기를 키워줘'를 요청해 안내를 시작한다.
4. 같은 안내 화면에서 '다시 말해줘'를 두 번 요청한다. 이벤트 전송은 비동기이므로 잠시 기다린다.
5. 다음 서버 안내가 발생하거나 같은 화면에서 새 작업을 시작할 때 항목의 이름과 위치를 확인하라는 추가 안내가 나오는지 확인한다. 반복 요청 자체는 직전 문장을 재생하므로 즉시 새 문구로 바뀌지는 않는다.
6. PC의 /docs에서 로그인 토큰으로 GET /api/v1/users/{user_id}를 호출해 profile.state_difficulties에 해당 화면의 consecutive_difficulties가 2 이상인지 확인한다. 토큰은 문서·로그·Git에 저장하지 않는다.
7. 동의를 철회한 뒤 화면별 이력이 비워지고 새 반복 요청이 저장되지 않는지 확인한다.

현재 실패·완료 이벤트의 Android 자동 수집은 연결되지 않았다. 완료 시 연속 어려움 초기화는 API 테스트로 검증되어 있으며 실기기 자동 회복 검증은 후속 연동이 필요하다.

## USB 없이 공용 서버 사용

Render 설정은 자동 배포가 꺼져 있으므로 Git push 후 서버를 별도로 배포해야 한다. 현재 커밋으로 배포하고 migration 20261007_09까지 적용한 뒤 HTTPS 서버의 /ready를 확인한다. APK는 다음처럼 실제 서버 주소를 지정해 다시 빌드·설치한다.

```powershell
.\gradlew.bat :app:assembleDebug -PCLICK_API_BASE_URL=https://YOUR-API-HOST
```

기존 APK의 서버 주소는 Git push로 바뀌지 않는다. 공용 서버 주소와 실제 배포 완료는 별도 확인해야 한다.
