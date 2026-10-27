# 앱–서버 통합 검증

`.github/workflows/backend.yml`은 Backend, AI-Agent, Frontend 변경 시 실행한다.
기존 백엔드·AI·PostgreSQL 검증에 Android 빌드와 앱 서비스 요청 재생 검증을 추가했다.

## 시작 흐름 회귀 테스트

`ClickOverlayServiceExecutionTest`는 Robolectric에서 실제 `ClickOverlayService`의
목표 준비 → 사용자 확인 → 지연 실행 → gateway 전송 경로를 실행한다. 네트워크,
화면 수집, 오버레이 표시만 대체하며 시작 여부를 판단하는 서비스 메서드는 그대로 사용한다.
서버 기능은 `Backend/docs/openapi.json`에서 읽는다.

- 준비가 완료되고 사용자가 확인하면, 기존 `requirementReview` 없이도 `start_task`를 보낸다.
- 확인 전, 입력 미완료, 미지원·취소 상태, 오래된 resolution에서는 시작하지 않는다.
- 중복 확인과 일시정지·취소 뒤의 지연 요청을 차단한다.
- 기존 v0.2 확인 흐름과 시작 후 `observe` 요청도 검증한다.
- 비전 실패 다섯 종류는 이미지 없이 거절 응답과 실패 코드만 보낸다.

## FE/BE 계약 테스트

Android 테스트가 실제 직렬화한 요청을
`Frontend/app/build/contract/testDebugUnitTest/`에 저장한다.
`Backend/contract_tests`는 이 파일을 읽어 인증된 FastAPI API에 전송한다.
서버가 새로 발급한 세션·resolution·episode·confirmation ID와 버전만 교체하고,
목표, 기능 버전, 화면 정보, 비전 실패 코드 등 나머지 필드는 Android 출력을 사용한다.
시작 후 첫 안내, 같은 episode의 관찰, 다섯 실패 코드의 정상 처리를 확인한다.
출력 파일이 없으면 건너뛰지 않고 실패한다.

저장소 루트에서 실행:

```sh
python -m pip install -c Backend/constraints-ci.txt setuptools
python -m pip install --no-build-isolation -c Backend/constraints-ci.txt -e './Backend[test]'
python Backend/scripts/export_openapi.py
cd Frontend
bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
cd ../Backend
python -m pytest -q contract_tests
```

다른 빌드 경로의 요청 파일을 사용할 때는 `CLICK_FRONTEND_CONTRACT_DIR`를 지정한다.
Android 테스트에는 JDK 17과 Android SDK 34가 필요하다. CI는 JDK 17.0.18+8,
Python 3.12.12, Android build-tools 34.0.0을 사용하며 Python 의존성은
`Backend/constraints-ci.txt`로 고정한다. 버전 변경 시 전체 CI를 다시 검증한다.

이 검증은 실제 기기의 접근성 권한, 화면 캡처, 음성 인식, 배포 서버 설정을 대신하지 않는다.
출시 전에는 설치된 앱에서 목표 확인부터 첫 안내까지 별도로 확인한다.
