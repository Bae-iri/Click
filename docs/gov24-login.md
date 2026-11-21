# 정부24 로그인 선택 안내 실험

주민등록등본 발급 안내 화면 탐색과 AI2 성공률 모니터는 별도 목표로 확장했다.
[실행 방법과 검증 범위](gov24-resident-navigation.md)를 참고한다.
정부24 생활 민원 20개는 [활성 Goal Type 목록](goal-type-keyword-catalog.md)에 등록하며,
로그인·등본의 공개 화면 안내와 나머지 준비 중 목표를 구분한다.

정부24 Android 앱(`kr.go.minwon.m`)의 공개 로그인 선택 화면을 안내한다. 사용자가 버튼·음성·텍스트로 사용할 수단을 고른다. 화면에 없는 인증 수단을 만들거나 개인정보를 입력하지 않는다. 주민등록등본 발급·신청, 실제 로그인 성공 검증은 이번 범위에 포함되지 않는다.


## 현재 로그인 안내 화면

### 범용 앱 안내에서 홈 검색창 때문에 멈추는 경우

`AppTaskController`의 “입력 화면은 클릭이 읽지 않아요”는 로그인 버튼 탐색 전에
범용 관측이 `protected`로 분류됐다는 뜻이다. 정부24 WebView 검색창에 검색 힌트나
리소스가 없으면 입력창 하나 때문에 홈 전체가 차단될 수 있다.

이 경우 정부24 패키지·같은 활성 창·홈 표식(정부24와 자주 찾는 서비스 또는 민원서비스)·
유일한 로그인 버튼을 확인하고, 비밀번호·복수 입력창·인증수단·동의·로그아웃 표시가 없을 때만
로그인 버튼 하나를 관측에 남긴다. 입력창과 그 하위 요소는 제외한다. 로그인 요청이면
모델 호출 없이 그 버튼을 직접 누르도록 표시한다. 이 예외는 기기 관측 객체에만 남으며
직렬화된 응답으로 부여할 수 없고, 자동 실행은 계속 금지한다.

별도 공개 로그인 안내도 전달받은 최신 관측으로 앱 진입 여부를 판단하며,
로컬 홈 탐색과 공개 필터가 같은 두 서비스 표식을 인정한다.

회귀 검사는 `Gov24HomeLoginObservationTest`, `Gov24HomeLoginControllerTest`,
`Gov24LoginEntryTest`와 오버레이의 오래된 런처 관측 사례로 확인한다.
이는 통제된 화면의 코드 재현이며, 사용자 기기의 실제 원인과 설치 APK 반영 여부는
폰을 연결해 홈 → 로그인 버튼 안내로 전환되는지 별도로 확인해야 한다.

`gov24-recovery.v1` 앱과 서버는 로그인·개인정보 입력 중에도 원래 민원 목표를 유지한다. 홈의 로그인 버튼은 `signed_out`, 로그아웃 버튼은 `signed_in` 근거로만 사용하며 로그아웃을 조작하지 않는다. 인증 중에는 `await_login` 또는 `await_public`에서 화면 전송을 멈추고, 허용된 공개 화면이 돌아오면 같은 요청을 이어간다. 선행 로그인이 필요한 민원은 로그인된 홈을 확인한 뒤 재개한다. 취소·일시정지한 작업은 자동으로 재개하지 않는다.

확인된 공개 검색창은 값이 있어도 값 전체를 로컬에 남기고 공개 메뉴만 내보낸다. 다른 입력 필드·비밀번호·동의 문구가 함께 보이면 계속 보호한다. 화면 재관측은 안내 횟수 예산을 소모하지 않으며, 실제 메뉴 변화·사용자 선택·행동만 예산에 포함한다. 민원 메뉴가 보이지 않으면 검증된 공개 화면에서 네이티브 표시 요청 또는 스크롤을 최대 6회 시도한다. 인증·미확인 화면에서는 자동 스크롤하지 않는다.

서버 OpenAPI에는 `PublicGuideRequest.recovery_version`, `PublicGuideScreen.auth_state`, `await_login`·`await_public` 응답이 함께 있어야 한다. 새 앱은 서버 호환성을 먼저 검사한다. 기존 앱은 기존 인계 정책을 유지하므로 **서버 재배포와 앱 업데이트를 함께 적용**한다. 실기기 검사 호스트는 런처·접근성 서비스가 없는 `uiTestDriver`를 사용하며 정부24 전용 사용자 앱을 별도로 설치하지 않는다.

시작을 확인하면 정부24를 자동으로 연다. 홈은 검색값을 읽지 않는 로컬 경로로 로그인 버튼을 찾고 표시한다. 버튼이 화면 밖이면 먼저 네이티브 스크롤로 가져온다. 홈 검색창 때문에 인증 화면으로 판단해 안내를 끝내지 않는다. 완료된 이전 공개 안내가 새 요청을 막으면 해당 진행 기록을 정리하고 대기 중인 요청을 이어간다.

진행 화면은 설정 안내와 같은 형태이며 별도 앱 열기·현재 화면 확인·직접 진행하고 끝내기 버튼을 두지 않는다. 로그인 방법 질문은 필요한 선택지만 표시한다. 인증기관 단계에서는 목록과 입력창을 다시 만들지 않고 **“원하는 민간 인증서를 선택해 주세요.”**라는 작은 안내만 표시한다. 사용자가 정부24 기본 화면에서 고르면 다음 화면을 따라가고, 실제 인증 단계에서 멈춘다.

홈 검색과 입력이 있는 인증 화면의 구분, 새 요청의 이전 상태 정리, 기본 인증서 선택 흐름은 [실기기 및 회귀 검증 기록](reviews/2026-11-18-gov24-native-entry.json)에 남긴다.

## 실행 경로

1. 서버에서 `CLICK_GOV24_GUIDE_ENABLED=1`을 설정하고 신규 Backend와 Android 앱을 함께 사용한다. 기본값과 Render 배포 설정은 켜짐이며, `0`으로 명시하면 비활성화한다. 구버전 서버에는 새 capability를 보내지 않는다. `/version`의 `public_guidance.gov24_login_enabled`로 실제 활성화 상태를 확인한다.
2. “정부24 로그인 도와줘” 요청을 확인하면 `public-guide.v1`, `/api/v1/agent/public-guide/commands`로 시작한다. 기존 목표 카탈로그와 승인 그래프를 임의로 확장하지 않는다.
3. 현재 화면의 공개 로그인 버튼을 의미 기반으로 정규화한다. 실제 정부24 WebView 버튼은 resource ID가 없어도 텍스트/접근성 설명·클릭 가능 여부·현재 window·bounds가 확인되면 후보가 된다. 전체 화면 원문은 보내지 않는다.
4. `open_login → choose_method → choose_provider → handoff`의 중간 목표를 사용한다. 간편인증 팝업에서는 뒤쪽 로그인 페이지의 선택지를 제외한다.
5. 질문에 버튼으로 답하면 현재 화면에 결합된 option ID를 사용한다. 음성·텍스트는 부정·정정·불확실 표현과 욕설을 포함한 문장을 유지해 Jev Choice API로 전달한다. 이름·전화번호·비밀번호 등은 일반 목표 해석과 같은 최소화 처리를 거치며, 화면 전체·노드 ID·좌표는 보내지 않는다. 알려진 단어만 추출하던 필터와 부정·불확실 단어를 이유로 한 사전 거절은 제거했다. 먼저 말한 인증기관 선호도 같은 문장으로 보존하고, 해당 기관이 실제 다음 화면에 나타난 경우에만 다시 대조한다.
6. 제브는 현재 후보 하나 또는 `insufficient`를 선택한다. 불명확·연결 실패·낮은 점수이면 임의 선택하지 않고 다시 묻는다. 질문 이후 화면이 바뀌면 기존 답으로 새 화면을 조작하지 않는다.
7. 선택한 실제 버튼을 강조하고 사용자가 누른다. 연결된 탭 뒤 화면 변화가 있으면 다음 선택 화면을 확인한다. 자동 인증·자동 입력은 하지 않는다.

## 중단 기준

- 개인정보·비밀번호·인증번호 등 입력 필드가 보임: 기기 내에서 `handoff / credential_input`으로 바꾸고 nodes를 비운다.
- 인증서 선택/인증 입력·모바일 신분증의 개인정보 동의를 알리는 검수된 문구가 보임: 항목 내용 대신 중단 사유만 보낸다.
- 알려진 외부 인증 앱으로 이동함: 해당 앱 내용을 보내지 않고 직접 진행으로 전환한다.
- 선택 이후 공개 후보로 인식하지 못하는 인증 화면: 직접 확인으로 넘긴다. 인증서 소유자 이름·계정 목록을 추측해 읽지 않는다.
- 구버전 앱의 `handoff`는 terminal 상태다. `gov24-recovery.v1`에서는 인증 경계를 대기로 유지하고 공개 근거가 돌아올 때만 재개한다. 사용자의 종료 응답은 `user_reported`이며 인증 경계 도착만으로 로그인 성공을 뜻하지 않는다.

현재 화면에 보이는 모바일 신분증·간편인증·공동인증서·금융인증서를 선택할 수 있다. 간편인증의 공개 기관 선택도 지원한다. 인증서 비밀번호, 이름/휴대전화 입력, 약관 동의, 인증 요청/승인, 인증서 발급·등록은 사용자가 직접 한다. 인증서가 없다는 답변에 임의 수단을 추천하지 않는다.

## 검증과 재현

기본 회귀는 외부 Jev를 호출하지 않는다.

```sh
python3 Backend/scripts/check_gov24_login.py
# 명시적으로 외부 Jev를 호출할 때만 --live 사용
python3 Backend/scripts/check_gov24_login.py --env-file Backend/.env --live
python3 -m pytest -c Backend/pyproject.toml Backend/tests/test_public_guide.py Backend/tests/test_coupang_browse.py
cd Frontend
sh gradlew :app:testDebugUnitTest --tests com.click.data.snapshot.Gov24LoginPrivacyTest --tests com.click.service.overlay.CoupangBrowseControllerTest
```

테스트폰에는 `-PCLICK_APPLICATION_ID=com.click.gov24pilot`로 이름이 **클릭 정부24 테스트**인 별도 APK를 빌드할 수 있다. 홈 아이콘과 접근성 서비스 이름을 일반 클릭과 구분한다. 기존 앱의 로그인·데이터를 덮어쓰지 않는다. `Gov24LoginDeviceTest`는 현재 정부24 화면을 읽고 로컬 필터를 검증한다. `expected=public` 또는 `expected=handoff`를 지정하며 입력·인증은 하지 않는다. 산출물은 개인정보를 제외한 semantic option·bounds 또는 빈 handoff 관측이다.

2026-11-18 연결 기기에서 정부24 2.5.121의 로그인 네 가지 방법·간편인증 기관 목록·기관 선택 뒤 입력 필드 3개를 확인했다. 사용자가 설정한 키로 실제 Jev `jev-1.14.0` 연결 검사를 추가했다. 공개 예시 선호 5건(외부 호출 4건, 로컬 불확실 응답 1건)이 기대 결과를 반환했다. 개인정보를 제외한 실제 기기 화면을 HTTP로 재생한 5개 확인 항목도 통과했다: 로그인 방법 질문, 불확실 답변 재질문, 자연어로 간편인증 선택, 관측된 기관 목록에서 기존 카카오 선호 이어가기, 인증 입력 전 중단. 전체 검사는 음성 재생 없이 수행했다.

이는 실기기 화면의 **서버 재생 검사**이며 실제 앱 전체를 조작해 인증까지 완료한 E2E 결과는 아니다. 이 재생 검사 시점에는 실제 로그인·민원 신청과 운영 배포를 수행하지 않았다. 결과와 한계는 [실연결 검증 기록](reviews/2026-11-18-gov24-login-live.json)에 남긴다.


## 실제 앱과 서버 반영 확인

별도 `com.click.gov24pilot` 앱 검증만으로 기존 클릭 앱(`com.clueclick.app`)이 업데이트된 것은 아니다. 실제 사용자 앱은 `CLICK_APPLICATION_ID`를 지정하지 않고 빌드·설치한다. 설치된 APK와 새 APK의 서명이 다르면 데이터 보존 업데이트가 거부되므로, 기존 서명키를 쓰거나 사용자에게 재설치로 인한 로그인·설정 초기화를 설명하고 승인을 받아야 한다.

서버는 푸시 여부와 별개로 `/version`의 `source_revision`이 배포할 커밋과 같은지, `public_guidance.gov24_login_enabled`가 `true`인지 확인한다. CI가 시작되지 못했을 때도 `checksPass` 자동 배포는 진행되지 않으므로 Render에서 수동 배포한 뒤 같은 값을 다시 확인한다.

실제 클릭 계정으로 기기에서 로그인한 뒤 아래 검사를 명시적으로 실행할 수 있다. 토큰은 기기 안에서만 사용하며 출력하지 않는다. 이 검사는 실제 홈 입력창에 요청을 입력하고 전송 버튼을 눌러 공개 로그인 방법 네 가지가 나타나는지 확인하며, 인증수단 선택·인증 요청·개인정보 입력은 진행하지 않는다. 일반 테스트 실행에서는 건너뛴다.

```sh
cd Frontend
sh gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 일반 클릭을 검사할 때는 별도 정부24 테스트 APK에서 실행한다.
# 대상 앱 자체를 instrumentation으로 재시작하면 접근성 서비스가 끊길 수 있다.
sh gradlew -PCLICK_APPLICATION_ID=com.click.gov24pilot :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e gov24Live true -e targetPackage com.clueclick.app \
  -e class com.click.data.remote.Gov24InstalledAppFlowTest \
  com.click.gov24pilot.test/androidx.test.runner.AndroidJUnitRunner
```


자연어 진입의 `resolve_goal` 요청도 서버가 공개한 `gov24_login` capability를 포함해야 한다. 이 요청은 처음부터 `device_context.capabilities`가 없는 형태이므로, 계약 인코더가 필요한 객체를 만들고 지원 정보를 넣는다. 공개 가이드 코드와 서버 활성화만으로는 이 전송 누락을 해결할 수 없다. 구버전 서버에는 미지원 capability를 추가하지 않는 회귀 검사도 유지한다.


직접 간편인증 버튼을 눌러 공개 선택지가 로그인 방법에서 인증기관으로 바뀌면, 강조 대상 탭 기록이 없어도 새 질문을 받는다. 공개 option ID 집합을 비교해 동일 화면의 이벤트는 반복 전송하지 않으며, 요청 중 도착한 화면 변화와 인증 경계도 응답 뒤 반영한다. 로그인 안내 제목 아래의 고정 설명은 표시하지 않는다.

정부24 세션 복원은 기존 설정 작업 카탈로그와 구분한다. 서버가 검증된 공개 가이드라고 표시한 알려진 목표만 복원하고 임의 목표는 거부한다. 디버그 APK의 `Gov24GuideProbeReceiver`는 `android.permission.DUMP`로 셸 접근만 허용하며 로그인 유효 여부·공개 후보 수·중단 상태만 반환한다. 계정 ID·토큰·화면 원문·인증 입력값은 반환하지 않는다.


2026-11-18 후속 실기기 검사에서는 이름이 바뀐 정부24 테스트 앱에서 현재 인증기관 질문 표시·고정 설명 삭제·“카카오로 할래” 텍스트의 실제 Jev 선택·카카오톡 공개 항목 탭·인증 경계 중단을 확인했다. 마지막 자연어 검사는 이미 열린 인증기관 목록에서 시작했으며, 직접 로그인 방법 전환 및 요청 중 화면 변경 처리는 별도 컨트롤러 회귀 검사로 검증했다. 최신 APK 설치 시각과 해시, 검사 범위는 [실제 앱 반영 기록](reviews/2026-11-18-gov24-runtime-rollout.json)에 구분해 남긴다.
