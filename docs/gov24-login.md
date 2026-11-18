# 정부24 로그인 선택 안내 실험

정부24 Android 앱(`kr.go.minwon.m`)의 공개 로그인 선택 화면을 안내한다. 사용자가 버튼·음성·텍스트로 사용할 수단을 고른다. 화면에 없는 인증 수단을 만들거나 개인정보를 입력하지 않는다. 주민등록등본 발급·신청, 실제 로그인 성공 검증은 이번 범위에 포함되지 않는다.

## 실행 경로

1. 서버에서 `CLICK_GOV24_GUIDE_ENABLED=1`을 설정하고 신규 Backend와 Android 앱을 함께 사용한다. 기본값은 꺼짐이며 구버전 서버에는 새 capability를 보내지 않는다.
2. “정부24 로그인 도와줘” 요청을 확인하면 `public-guide.v1`, `/api/v1/agent/public-guide/commands`로 시작한다. 기존 목표 카탈로그와 승인 그래프를 임의로 확장하지 않는다.
3. 현재 화면의 공개 로그인 버튼을 의미 기반으로 정규화한다. 실제 정부24 WebView 버튼은 resource ID가 없어도 텍스트/접근성 설명·클릭 가능 여부·현재 window·bounds가 확인되면 후보가 된다. 전체 화면 원문은 보내지 않는다.
4. `open_login → choose_method → choose_provider → handoff`의 중간 목표를 사용한다. 간편인증 팝업에서는 뒤쪽 로그인 페이지의 선택지를 제외한다.
5. 질문에 버튼으로 답하면 현재 화면에 결합된 option ID를 사용한다. 음성·텍스트는 알려진 인증 수단 이름·부정/불확실 표현·번호만 추출해 기존 Jev Choice API로 전달한다. 제브에는 화면 전체·노드 ID·좌표·사용자 원문을 보내지 않는다. 먼저 말한 공개 인증기관 선호는 해당 기관이 실제 다음 화면에 나타난 경우에만 다시 대조해 이어간다.
6. 제브는 현재 후보 하나 또는 `insufficient`를 선택한다. 불명확·연결 실패·낮은 점수이면 임의 선택하지 않고 다시 묻는다. 질문 이후 화면이 바뀌면 기존 답으로 새 화면을 조작하지 않는다.
7. 선택한 실제 버튼을 강조하고 사용자가 누른다. 연결된 탭 뒤 화면 변화가 있으면 다음 선택 화면을 확인한다. 자동 인증·자동 입력은 하지 않는다.

## 중단 기준

- 개인정보·비밀번호·인증번호 등 입력 필드가 보임: 기기 내에서 `handoff / credential_input`으로 바꾸고 nodes를 비운다.
- 인증서 선택/인증 입력·모바일 신분증의 개인정보 동의를 알리는 검수된 문구가 보임: 항목 내용 대신 중단 사유만 보낸다.
- 알려진 외부 인증 앱으로 이동함: 해당 앱 내용을 보내지 않고 직접 진행으로 전환한다.
- 선택 이후 공개 후보로 인식하지 못하는 인증 화면: 직접 확인으로 넘긴다. 인증서 소유자 이름·계정 목록을 추측해 읽지 않는다.
- `handoff` 뒤에는 컨트롤러가 terminal 상태가 되어 화면을 더 전송하지 않는다. 사용자의 종료 응답은 `user_reported`, 인증 경계 도착은 `handoff`이며 로그인 성공을 뜻하지 않는다.

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

테스트폰에는 `-PCLICK_APPLICATION_ID=com.click.gov24pilot`로 별도 APK를 빌드할 수 있다. 기존 앱의 로그인·데이터를 덮어쓰지 않는다. `Gov24LoginDeviceTest`는 현재 정부24 화면을 읽고 로컬 필터를 검증한다. `expected=public` 또는 `expected=handoff`를 지정하며 입력·인증은 하지 않는다. 산출물은 개인정보를 제외한 semantic option·bounds 또는 빈 handoff 관측이다.

2026-11-18 연결 기기에서 정부24 2.5.121의 로그인 네 가지 방법·간편인증 기관 목록·기관 선택 뒤 입력 필드 3개를 확인했다. 사용자가 설정한 키로 실제 Jev `jev-1.14.0` 연결 검사를 추가했다. 공개 예시 선호 5건(외부 호출 4건, 로컬 불확실 응답 1건)이 기대 결과를 반환했다. 개인정보를 제외한 실제 기기 화면을 HTTP로 재생한 5개 확인 항목도 통과했다: 로그인 방법 질문, 불확실 답변 재질문, 자연어로 간편인증 선택, 관측된 기관 목록에서 기존 카카오 선호 이어가기, 인증 입력 전 중단. 전체 검사는 음성 재생 없이 수행했다.

이는 실기기 화면의 **서버 재생 검사**이며 실제 앱 전체를 조작해 인증까지 완료한 E2E 결과는 아니다. 실제 로그인이나 민원 신청을 수행하지 않았고 운영 배포도 하지 않았다. 결과와 한계는 [실연결 검증 기록](reviews/2026-11-18-gov24-login-live.json)에 남긴다.
