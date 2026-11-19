# Click Backend

FastAPI 기반 Android 사용 안내 서버입니다.

**입력 분류:** `resolve_goal`은 Docker/Render 배포에서 Jev를 사용한다. 직접 Python 실행의 기본값은 자체 서버이며 환경변수로 provider를 명시할 수 있다. 화면 판단용 `AI1_UI_SELECTOR_PROVIDER`와 요청 해석용 `GOAL_CLASSIFIER_PROVIDER`는 별개다. `/version.goal_interpretation`의 `provider`와 `configured`로 설정 반영 여부를 확인한다. `configured=true`는 환경 설정 검증이며 실제 모델 연결·정확도 검증은 아니다. 전송 전 개인정보 패턴 마스킹, 승인 후보·응답 검증이 적용되며 추론 실패 시 질문 흐름으로 돌아간다. [자체 서버 설정](inference/README.md#입력-분류-llm만-연결하기), [Jev 설정과 연결 점검](inference/jev.md)을 참고한다.

```powershell
python -m pip install -e ".[test]"
.\scripts\dev.ps1
```

macOS/Linux에서도 같은 개발 프로필을 사용합니다. 화면 판단과 요청 해석을 Jev로 명시하고,
`instance/validation`에 영구 DB·기존 암호화 키·서버 소스와 그래프 hash·APK 연결 주소를 함께 고정합니다.
`.env`의 `TYPESAFE_API_KEY`가 없으면 준비를 중단합니다. 키 값은 출력하지 않습니다.

```sh
python scripts/validation.py prepare --env-file .env --profile jev --gov24 --port 8001
python scripts/validation.py serve
# 다른 터미널에서 서버와 APK를 만들 manifest의 일치를 확인합니다.
python scripts/validation.py check
```

Frontend의 `assembleValidation`은 생성된 `Backend/instance/validation/environment.json`을 읽어
동일한 주소·환경 fingerprint를 APK에 넣습니다. 소스·migration·모델 설정이 달라지면 다시 준비하고
서버와 APK를 함께 갱신해야 합니다. 로컬 `.env` 변경은 원격 Render 서버에 반영되지 않습니다.
DB의 기본 메모리 설정은 명시적으로 만든 테스트 인스턴스용이며 위 개발 실행 경로는 파일 DB를 사용합니다.

[개발·운영 안내](docs/README.md)에 현재 상태·실행·API·개인정보·모델 연결·배포·검증을 모았습니다. 정확한 API 필드는 [OpenAPI](docs/openapi.json), AI2 상세는 [Agent 2 안내](../AI-Agent/src/ai_agent/graph_proposal/README.md)를 참고하세요.
