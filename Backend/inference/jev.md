# Jev 목표 분류 연결

2026-10-27. TypeSafe의 Jev를 기존 선택적 목표 해석 위치에 연결한다. 새 SDK 없이 Backend의 기존 `httpx`로 `POST https://api.typesafe.ai/v1/systemone`을 호출한다. 현재 기준 고정 모델은 `jev-1.14.0`이다.

## 설정

1. [TypeSafe 콘솔](https://console.typesafe.ai/)에서 API 키를 발급한다.
2. 개발 환경에서는 기존 `Backend/.env`에 아래 항목을 추가하거나 수정한다. 기존 DB 등의 설정을 덮어쓰지 않는다. 배포 환경에서는 Backend 서비스의 환경변수/secret 설정을 사용한다.

```dotenv
GOAL_CLASSIFIER_PROVIDER=jev
TYPESAFE_API_KEY=발급받은_키
JEV_MODEL=jev-1.14.0
JEV_MIN_CONFIDENCE=0.8
```

키는 Android 앱, Git 또는 채팅에 넣지 않는다. `JEV_MIN_CONFIDENCE=0.8`은 초기 실험 설정이며 Click 한국어 데이터로 검증한 합격선이나 정답률이 아니다. `jev-latest`처럼 자동 변경되는 별칭은 이 어댑터에서 허용하지 않는다.

3. Backend 가상환경에서 합성 문장 연결 점검을 실행한다.

```sh
cd Backend
python scripts/check_goal_llm.py --env-file .env
```

확대·축소·범위 누락의 3건을 실제 선택된 provider에 요청한다. 키/원문/응답/전송 예외는 출력하지 않는다. `CONFIG_ERROR`는 환경변수를 확인하고, `FAIL`은 인증·네트워크·응답 형식·모델 판단·임계값을 조사한다. 오류를 숨기려고 임계값부터 낮추지 않는다. 이 점검은 한국어 품질 평가가 아니다. 기존 셸 환경변수가 `.env` 값보다 우선한다.

4. 같은 설정으로 Backend를 재시작한다.

```sh
python -m uvicorn app.main:app --env-file .env --reload
```

`--reload`는 개발용이다. 운영 서버는 기존 실행 방식으로 해당 환경변수를 주입해 재시작한다. 단순히 `.env` 파일을 만드는 것만으로 이미 실행 중인 서버에 반영되지는 않는다.

## 적용 범위

- `goal_preparation`과 기존 `goal_resolution`이 동일한 provider 선택을 사용한다. 확실한 키워드 요청 등 기존 규칙으로 해결되는 입력에는 모델 호출이 생기지 않을 수 있다.
- 승인 후보의 목표를 Choice로 선택한다. `clarify`, `unsupported`, `cancel`, `multiple` 선택과 낮은 confidence/잘못된 응답/실패는 기존 추가 질문으로 돌아간다.
- 방향은 선택한 목표의 슬롯에서 가져오며 명시적 방향과 충돌하면 거절한다. 범위와 앱 이름은 로컬의 명시적 값 추출을 사용한다. 자유로운 앱 이름 생성이나 미지정 범위 추론을 하지 않는다.
- 실제 graph 선택, 사용자 시작 확인, AI1 안내와 완료 검증은 기존 Backend/runtime이 담당한다. 모델 결과가 실행을 승인하지 않는다.
- STT는 기존 자체 서버를 사용한다. 최종 만족도 응답의 프런트엔드 문자열 분류는 이번 연결 범위에 포함되지 않는다.
- provider 전환은 서버 시작 시 적용된다. `GOAL_CLASSIFIER_PROVIDER=self_hosted`로 바꾸고 재시작하면 기존 자체 서버 분류기를 사용한다. 오류 시 provider 간 자동 fallback은 없다.

## 데이터 처리 경계

기본값 `self_hosted`는 기존 자체 서버 경계를 유지한다. `jev`를 명시적으로 선택하면 개인정보 패턴을 마스킹한 요청 텍스트와 승인 작업 정의를 TypeSafe 외부 API로 전송한다. 원음·화면·계정/세션 ID는 전송하지 않는다. 패턴 마스킹은 완전 익명화를 보장하지 않으므로 실제 사용자 적용 시 안내·동의와 운영 정책에 이 외부 텍스트 처리를 반영해야 한다. 기존의 ‘전사문도 우리 서버 밖으로 보내지 않는다’는 정책과 동일한 처리가 아니다.

고정 HTTPS 호스트, 리디렉션 금지, 환경 프록시 사용 금지, 8초 요청 timeout을 사용하며 자동 재시도나 요청/응답 로깅을 추가하지 않는다. TypeSafe의 고객 데이터 학습 미사용과 무보관 처리는 다른 조건이다. 무보관은 별도 기업용 설정을 확인해야 한다.

## 확인된 범위와 남은 검증

자동 테스트는 가짜 TypeSafe HTTP 응답으로 프로토콜, 마스킹, 오류/보류, 명시적 값 검증, 인증된 목표 준비 API와 시작 확인 경계를 검사한다. 실제 계정 인증·유료 API 응답·한국어 품질·실기기 동작을 대신하지 않는다. 실제 API 연결은 위 합성 점검을 해당 계정으로 실행한 뒤 확인한다.

공식 근거: [API](https://docs.typesafe.ai/api/system-one), [Choice](https://docs.typesafe.ai/primitives/choice), [모델·버전](https://docs.typesafe.ai/models), [Confidence](https://docs.typesafe.ai/confidence), [데이터 처리](https://help.typesafe.ai/articles/5974925716-how-is-submitted-data-used-or-retained).
