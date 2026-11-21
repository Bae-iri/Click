# Click Backend

FastAPI 기반 Android 사용 안내 서버입니다.

범용 앱의 다음 동작 계획과 완료 근거 확인(`/api/v1/agent/app-guide/task`)에 GPT를 쓰려면 서버 환경에 `APP_TASK_PROVIDER=openai`와 `OPENAI_API_KEY`를 설정합니다. 기본 모델은 `gpt-6.2-sol`이며 `APP_TASK_OPENAI_MODEL`로 바꿀 수 있습니다. 기존 Gemini 실험은 `APP_TASK_PROVIDER=gemini`로 재현할 수 있습니다.

OpenRouter의 Qwen3.8-Flash로 같은 앱 과제를 실행하려면 서버에 `APP_TASK_PROVIDER=openrouter`와 `OPENROUTER_API_KEY`를 설정합니다. 모델 ID는 `qwen/qwen3.8-flash`이며 기존 완료 근거 검증을 거칩니다.

GPT를 주 플래너로 쓰면서 단순한 첫 메뉴만 Qwen에 맡기려면 `APP_TASK_PROVIDER=openai`에서 `OPENROUTER_API_KEY`를 설정하고 `APP_TASK_OPENROUTER_SHORT_MENU=1`을 켭니다. 이때 화면에 보이는 버튼 ID 하나를 요청하고 기존 검증을 거칩니다. 같은 옵션은 `APP_TASK_PROVIDER=openrouter`에서도 첫 메뉴의 응답을 줄입니다.

`APP_TASK_OPENROUTER_SHORT_MENU=screen`은 "파일 앱에서 최근 파일 화면을 열어줘"처럼 목적지 하나로 가는 과제에서 첫 메뉴뿐 아니라 **매 화면**을 Qwen의 ID 하나로 처리합니다. 후보는 지금 누를 수 있는 컨트롤과, 목표의 목적지 이름(또는 그 짧은 형태)과 같은 앱바 제목입니다. 목적지 이름이 아직 버튼으로 남아 있는 화면(예: "알림" 화면의 "앱 알림" 행)에서는 제목을 후보로 주지 않습니다. 고른 제목도 기존 완료 근거 검증과 기기 검증을 거칩니다. 여러 단계 과제("열고 … 해줘")와 복구 중인 요청은 기존 플래너로 갑니다.

같은 요청을 로컬 Qwen3-1.7B로 실험하려면 백엔드와 같은 호스트에서 아래처럼 llama.cpp 서버를 띄운 뒤 `APP_TASK_PROVIDER=local`을 사용합니다. 해당 경로는 클라우드 API 키 없이 루프백의 Responses API를 호출합니다. 실험 모델 파일은 별도로 준비합니다. [공식 Qwen 모델](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF)

```sh
llama-server -m Qwen3-1.7B-Q8_0.gguf --alias qwen3-1.7b --host 127.0.0.1 --port 18769 --ctx-size 8192 --parallel 1 --reasoning off --temp 0 --seed 42
```

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
