# 카카오 가입·로그인 설정

Click의 시작 화면은 카카오 로그인을 기본으로 제공하고 이메일 가입·로그인을 보조로 제공한다.
전화번호 수집, 문자 발송, SMS 인증 서비스는 추가하지 않는다.
카카오 최초 로그인은 별도 비밀번호 없이 계정을 만들고, 이후 로그인은 같은 계정으로 돌아온다.
이름·이메일·전화번호·생년월일을 카카오에서 요청하지 않으며, 회원번호만 사용한다.
이메일 가입에서도 이름·나이·직업은 선택이고 '입력 없이 가입하기'로 건너뛸 수 있다.

## 최초 설정

1. [카카오디벨로퍼스](https://developers.kakao.com/)에서 앱을 만들고 카카오 로그인을 활성화한다.
2. 네이티브 앱 키의 Android 플랫폼에 패키지명 `com.click`과 실제 APK 서명 인증서의 **카카오 키 해시(Base64 SHA-1)**를 등록한다.
   디버그 APK와 정식 배포 APK의 키 해시는 다르다. Play App Signing을 쓰면 배포 앱 서명 키도 등록한다.
3. 앱 ID(숫자)를 Render 백엔드의 `KAKAO_APP_ID` 환경 변수로 설정한다. 네이티브 앱 키와 다른 값이다.
4. 프런트엔드 빌드 환경의 `KAKAO_NATIVE_APP_KEY`에 32자리 네이티브 앱 키를 설정하고 APK를 다시 빌드한다.
   Gradle 속성 `-PKAKAO_NATIVE_APP_KEY=...`도 지원한다. 앱 키를 저장소에 커밋하지 않는다.
5. 백엔드의 기존 pre-deploy 명령 `python scripts/render_start.py --migrate`로 새 migration까지 적용해 배포한다.
   실제 앱에서 카카오 가입 → 로그아웃 → 같은 계정으로 로그인 → 기록 유지 → 회원탈퇴를 점검한다.

PowerShell 빌드 예시(`Frontend` 디렉터리):

```powershell
$env:KAKAO_NATIVE_APP_KEY = "<카카오 네이티브 앱 키>"
.\gradlew.bat :app:assembleDebug
```

네이티브 SDK가 `kakao<네이티브 앱 키>://oauth` 리다이렉트를 처리한다.
카카오톡 사용이 가능하면 카카오톡을 사용하고, 설치되지 않았거나 사용 불가능하면 카카오계정 브라우저 로그인을 제공한다.
사용자가 취소하면 브라우저 로그인을 강제로 열지 않는다.
앱 키가 없는 빌드에서는 카카오 버튼을 눌렀을 때 준비 중임을 안내하며 이메일 경로를 사용할 수 있다.
서버 앱 ID 미설정은 503이고 계정을 생성하지 않는다.

## 계정과 검증

`POST /api/v1/auth/kakao`는 `{"access_token":"<SDK가 발급한 카카오 토큰>"}`만 받는다.
백엔드는 Kakao의 고정 HTTPS endpoint `/v1/user/access_token_info`에서 회원번호·앱 ID·유효기간을 확인한다.
다른 앱의 토큰, 만료된 토큰, 클라이언트가 보낸 임의 회원번호는 허용하지 않는다.
응답은 기존 이메일 로그인과 같은 Click access/refresh 토큰이고, 카카오 토큰은 DB나 로그에 저장하지 않는다.

기존 이메일 계정은 그대로 유지한다. 카카오 회원번호는 별도 계정으로 취급하며 이메일 일치를 근거로 계정을 합치지 않는다.
기존 이메일 사용자에게는 이메일 로그인을 안내한다. 로그인 상태를 확인한 계정 연결 기능은 이번 변경에 포함하지 않는다.
새 `kakao_identities` 테이블은 회원탈퇴 시 삭제되고, 기존 세션은 migration 이후에도 보존된다.
카카오 계정이 있는 DB의 구버전 스키마 downgrade는 데이터 유실을 막기 위해 거부한다.

## 검증 범위

단위·API 테스트는 Kakao 응답을 모의하여 신규·반복 로그인, 다른 앱/만료 토큰 거절, 장애·시간 초과,
기존 이메일 계정, 토큰 갱신·폐기·탈퇴, DB migration의 기존 세션 보존을 확인한다.
카카오디벨로퍼스 앱이 아직 등록되지 않은 상태에서는 실제 카카오 계정 로그인 성공을 검증한 것으로 기록하지 않는다.

공식 문서:
[Android SDK](https://developers.kakao.com/docs/ko/kakaologin/android),
[토큰 정보 조회](https://developers.kakao.com/docs/ko/kakaologin/rest-api#req-access-token-info),
[로그인 버튼 가이드](https://developers.kakao.com/docs/ko/kakaologin/design-guide).
