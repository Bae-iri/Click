# 자체 STT와 목표 해석 LLM 실행

배포 후 실제 추론 점검 명령과 Frontend/AI-Agent/Backend 담당별 확인 항목은 [STT·OCR 인수 문서](../docs/README.md#모델-연결)를 참고한다. API 계약 확인만으로 실제 STT 성공을 기록하지 않는다.

Click의 음성 인식은 **Whisper large-v3 (`faster-whisper`/CTranslate2)**, 목표 해석은 **키워드 매핑 실패 또는 그래프 입력 정보 누락 시 자체 LLM**을 사용한다. API 연결 코드와 독립 STT 실행 프로세스를 제공한다. 실제 가중치 추론·GPU 메모리·한국어 품질·지연은 별도 서버에서 검증해야 한다.

2026-10-30 속도 개선과 측정 방법은 [Whisper 지연 개선](../../docs/stt-latency.md)을 참고한다.

## 입력과 실행 경계

1. 앱은 16kHz mono PCM16 WAV를 메모리에서 녹음한다. 업로드 전에 API의 OpenAPI 응답이 `provider=self_hosted`, `model=openai/whisper-large-v3`로 제한되는지 검사한다. 구버전 외부 STT 서버나 확인 불가능한 서버에는 음성을 보내지 않는다.
2. 인증된 `/api/v1/speech/transcriptions`가 지정한 자체 STT 서버에만 전송한다. 최대 20초·650,000바이트이며 앱 녹음은 최대 16초다. MP3 등 다른 형식은 이 초기 연결에서 지원하지 않는다.
3. v0.3 `resolve_goal`의 `preparation_version=goal-inputs.v1` 경로는 키워드 매핑 후 그래프 입력 조건도 검사한다. 매핑 실패 또는 입력 정보 누락 시 자체 LLM에 개인정보 패턴을 마스킹한 발화·목표 후보·필드 정의를 보낸다. 화면 트리·이미지·음성·대화 이력·사용자 ID는 보내지 않는다. 구버전 준비 계약 없는 경로는 기존의 키워드 실패 fallback을 유지한다.
4. 부정 요청·키워드 동점·이미 매핑된 미지원 목표는 LLM으로 실행 가능한 목표를 만들어내지 않는다. 새 준비 경로에서 LLM은 목표 ID와 부분 입력값을 제안한다. 누락된 적용 범위는 질문으로 확인하고, 모든 필수값이 모인 뒤 서버가 승인 그래프를 선택한다. 자세한 [입력 계약](../../docs/goal-progress-result-flow.md)을 참고한다.
5. LLM 결과는 실행 승인이 아니다. 앱에서 대상·범위·방향을 확인하고 사용자가 시작을 눌러야 한다. 특정 앱 요청을 휴대폰 전체 변경으로 자동 바꾸지 않는다.

현재 승인된 후보는 글자 크기 증가·감소 두 개다. 나머지 카탈로그 항목은 모델이 임의로 실행 가능 상태로 승격할 수 없다. 모델의 의미 해석 정확도와 프롬프트 공격 저항성이 테스트 대역 검증으로 입증되는 것은 아니다.

## 입력 분류 LLM만 연결하기

STT/TTS 설정과 독립적으로, 사용자가 입력하거나 전사된 **텍스트의 목표와 부분 입력값**을 해석한다. 별도 API를 추가하지 않고 기존 인증된 `POST /api/v1/agent/commands`의 `command_type=resolve_goal`, `preparation_version=goal-inputs.v1`에서 호출한다. 앱에는 모델 서버 주소나 인증키를 넣지 않는다.

자체 추론 서버를 준비한 뒤 **Backend 실행 환경**에 아래 세 값을 설정한다. 현재 기본값은 비어 있으며 모델 가중치를 자동 다운로드하거나 외부 모델로 우회하지 않는다.

```dotenv
SELF_HOSTED_LLM_URL=https://<우리-추론-호스트>/v1/chat/completions
SELF_HOSTED_LLM_TOKEN=<추론-서버의-Bearer-인증값>
SELF_HOSTED_LLM_MODEL=<서버가-제공하는-모델-ID>
```

원격 서버는 HTTPS가 필수이며 개발용 `localhost`, `127.0.0.1`, `::1`만 HTTP를 허용한다. URL은 전체 endpoint 경로여야 한다. Bearer 인증, 비스트리밍 Chat Completions, `response_format={"type":"json_object"}`를 지원하는 서버를 사용한다. 모델 프로세스는 Backend와 분리하고, 아래 형태의 JSON을 `choices[0].message.content` 문자열에 담아 `finish_reason=stop`으로 응답해야 한다.

```json
{"goal_type":"settings.font_size.increase","inputs":{"direction":"increase"}}
```

미지원·불명확한 요청은 `{"goal_type":null,"inputs":{}}`로 반환한다. Backend는 승인 후보에 없는 ID, 추가 키, 중복 JSON 키, 도구 호출, 허용 목록 밖의 필드/값, 입력에 없는 앱 이름, 선택한 목표와 반대인 방향을 거부한다. 범위가 명시되지 않았으면 모델이 `scope=system`을 넣어도 버리고 사용자에게 질문한다. 명확한 키워드와 필수 입력이 이미 있으면 모델 호출이 필요 없다. 연결/응답 오류도 실행으로 이어지지 않고 기존 질문 흐름으로 처리된다.

서버 준비 후 Backend 디렉터리에서 실행한다. Backend 의존성이 설치된 Python 환경을 사용하고, 환경변수를 이미 주입했다면 `--env-file`을 생략한다. 기존 환경변수는 파일 값보다 우선한다.

```bash
python scripts/check_goal_llm.py --env-file .env
```

점검은 합성 문장 3개만 보내며 사용자 DB·세션에 접근하지 않는다. 목표 증가/감소와 누락 범위 처리를 확인한다. 종료 코드 `0`은 통과, `1`은 응답/연결 검사 실패, `2`는 설정 오류다. URL·인증값·요청/응답 본문은 출력하지 않는다. 실제 서버가 없는 환경에서는 설정 오류가 정상이며, 실제 모델의 한국어 정확도·지연·동시 처리 성능은 별도 검증 대상이다.

전송되는 텍스트 사본에는 이메일, 전화번호, 긴 숫자, URL과 `이름은 …`, `주소는 …`, `비밀번호는 …` 같은 패턴을 `[REDACTED]`로 바꾼다. 정규식으로 모든 이름·주소·민감 정보를 검출할 수는 없으므로 **완전한 익명화를 보장하지 않는다**. 추론 클라이언트는 프롬프트/응답을 직접 기록·저장하지 않지만 기존 Backend 큐의 `raw_goal`과 준비 응답의 `source_text`는 별도로 저장된다. 이 변경은 그 보관 정책을 바꾸지 않는다. 운영 추론 서버와 프록시의 본문 로그·APM 수집도 별도로 끄고 접근/보관 정책을 적용해야 한다.

## STT 서버 준비

기존 `render.yaml` API 서버는 512MB 구성이다. 모델을 이 API 프로세스에 적재하지 않는다. 우리 관리 범위의 별도 머신에서 Python 3.12 환경을 만든다. 아래 명령은 저장소의 `Backend` 디렉터리 기준이다.

```bash
python3.12 -m venv .venv-asr
.venv-asr/bin/pip install -r inference/requirements.txt
```

실행기는 `faster-whisper==1.2.1`이며 PyTorch를 사용하지 않는다. NVIDIA GPU는 CTranslate2에 맞는 CUDA 12/cuDNN 9를 준비한다. 기본값은 `WHISPER_DEVICE=cuda`, `WHISPER_COMPUTE_TYPE=float16`이다. CPU는 `WHISPER_DEVICE=cpu`, `WHISPER_COMPUTE_TYPE=int8`로 설정한다. 실제 자원·한국어 지연은 측정 전이다.

별도 배포 준비 환경에서 `openai/whisper-large-v3`의 CTranslate2 변환본인 `Systran/faster-whisper-large-v3`를 내려받아 revision과 파일 SHA-256 목록을 보관한다. 서비스 실행 중에는 다운로드하지 않는다. 승인한 revision으로 다운로드하는 예:

```python
from huggingface_hub import snapshot_download
snapshot_download("Systran/faster-whisper-large-v3", revision="<검증한 commit SHA>",
                  local_dir="/srv/models/faster-whisper-large-v3")
```

`model.bin`, `config.json`, `tokenizer.json`, `preprocessor_config.json`이 모두 있어야 시작한다. 원본 Transformers 형식이나 Qwen 가중치는 사용할 수 없다. 모델 디렉터리를 읽기 전용으로 제공하고, 비밀 관리 시스템에서 32자 이상의 무작위 `SELF_HOSTED_STT_TOKEN`을 주입한다. [worker 설정 예시](.env.example)를 기준으로 다음 파일은 머신에만 보관하며 저장소에 커밋하지 않는다.

```dotenv
# /etc/click/asr.env
WHISPER_MODEL_PATH=/srv/models/faster-whisper-large-v3
WHISPER_DEVICE=cuda
WHISPER_COMPUTE_TYPE=float16
SELF_HOSTED_STT_TOKEN=<32자 이상의 공유 비밀>
```

```bash
.venv-asr/bin/uvicorn inference.whisper_stt:app --env-file /etc/click/asr.env \
  --host 127.0.0.1 --port 8091 --workers 1 --no-access-log
```

Windows 로컬 환경은 별도 Python 환경과 `inference/.env`를 사용한다. 이 PC의 실행 예는 다음과 같다. worker 설정은 `WHISPER_DEVICE=cpu`, `WHISPER_COMPUTE_TYPE=int8`이며, 모델 경로는 `C:/click_server/models/faster-whisper-large-v3`다. 가중치·설치 환경은 Git 저장소 밖에 보관한다.

```powershell
# Backend 디렉터리에서 worker를 API와 별도로 실행
& C:\click_server\.venv-asr\Scripts\python.exe -m uvicorn inference.whisper_stt:app --env-file inference/.env --host 127.0.0.1 --port 8091 --workers 1 --no-access-log
# Backend 환경에서 설정만 확인; WAV가 없으면 추론하지 않는다.
python scripts/check_stt.py --env-file .env
# 실제 한국어 WAV와 기대 문장을 확보한 뒤 실행
python scripts/check_stt.py --env-file .env --wav /path/to/test-ko.wav --expected-text "글자 크게 해줘"
```

`/health`는 모델 로드가 끝나야 응답한다. 모델 로더는 오프라인 모드에서 로컬 디렉터리만 사용하며 Hugging Face·ONNX Runtime telemetry를 비활성화한다. worker는 `language=ko`, `task=transcribe`, VAD, temperature 0, beam 1(greedy), 이전 문맥 미사용으로 요청당 하나의 음성을 전사한다. 무음·빈 결과는 422, 동시 요청은 503을 반환한다. 지연 생성되는 segment를 잠금 안에서 모두 소비한다. 원음이나 전사 결과를 파일에 저장하지 않는다. API의 STT 대기 시간은 30초다. 대기가 끝나도 이미 시작된 모델 연산이 즉시 취소되는 것은 아니다.

무음일 때 worker는 `422 {"detail":{"code":"NO_SPEECH"}}`, Backend는 기존 오류 envelope의 `error.code=NO_SPEECH`로 전달한다. 앱은 재녹음을 안내한다. 잘못된 WAV의 `422 INVALID_REQUEST`, 서버 장애의 503, 시간 초과의 504와 구분한다. 모델이 잘못된 타입이나 지나치게 긴 결과를 반환한 경우는 무음으로 취급하지 않는다. 음성 없는 자동 테스트는 이 오류 전달 계약을 검증하며 실제 VAD·한국어 인식 품질을 입증하지 않는다.

2026-10-27 Windows 로컬 준비 확인: Python 3.12.13, faster-whisper 1.2.1, CPU/int8로 revision `edaa852ec7e145841d8ffdb056a99866b5f0a478`의 5개 파일을 내려받고 SHA-256을 검사했다. 모델 로드 후 worker `/health`와 Backend `/health`·`/ready`가 200이며 모델 ID가 일치한다. 올바른 토큰의 잘못된 WAV는 추론 전에 422, 잘못된 토큰은 401로 거절했다. 기동 확인은 약 19.6초, 대기 상태 working set 약 1.67GiB·private commit 약 6.70GiB였다. 전사 중 최대 메모리 측정은 아니다. 테스트 WAV와 기대 문장이 없어 실제 한국어 전사·전사 지연·휴대폰 검증은 진행하지 않았으며, 이 설정은 로컬용으로 운영 서버에 배포하지 않았다.

## API 서버 설정

`Backend/.env.example`에 추가한 값을 API 실행 환경에 넣는다. URL은 **완전한 endpoint 경로**다. API와 worker가 다른 머신이면 loopback 주소 대신 API에서 접근 가능한 우리 서버 주소와 TLS 또는 암호화된 사설 연결을 사용한다.

```dotenv
SELF_HOSTED_STT_URL=http://127.0.0.1:8091/v1/transcriptions
SELF_HOSTED_STT_TOKEN=<worker와 같은 비밀>
SELF_HOSTED_LLM_URL=http://127.0.0.1:8092/v1/chat/completions
SELF_HOSTED_LLM_TOKEN=<우리 LLM 서버 인증 비밀>
SELF_HOSTED_LLM_MODEL=<실제로 서비스 중인 모델 ID>
```

LLM 서버는 JSON 출력과 `/v1/chat/completions` 프로토콜을 지원해야 한다. 이 프로토콜 사용은 외부 OpenAI API 사용을 뜻하지 않는다. LLM 실행 소프트웨어와 가중치는 기존 팀 서버에 맞춰 설정한다. 모델 이름을 추측해 기본값으로 넣지 않았다. 추론 모드에서 설명·thinking 토큰 대신 짧은 JSON을 반환하도록 서버를 구성한다. 잘린 출력이나 `finish_reason != stop`이면 확인 질문으로 돌아간다.

STT 설정이 없거나 오류가 나면 503/504/502를 반환하며 외부 제공자로 재시도하지 않는다. LLM 설정 없음·시간 초과·잘못된 응답은 `needs_clarification`이다. HTTP 리다이렉트와 환경 프록시를 따라가지 않는다. URL만으로 서버 소유권을 증명할 수는 없으므로 운영 egress를 우리 추론 서버로 제한한다.

## 배포와 검증

1. worker를 준비하고 모델 로드, 한국어 실제 WAV, 처리 지연과 메모리를 확인한다. 프록시의 요청 본문 로깅·APM 캡처·디스크 버퍼링을 끄고 본문 크기를 1MiB 이하로 제한한다. 인증된 API만 worker에 접근하도록 구성한다.
2. 새 Whisper worker를 별도 포트에서 확인한 뒤 API의 `SELF_HOSTED_STT_URL`을 새 endpoint로 전환하고 배포한다. `/openapi.json`의 STT provider/model 상수와 v0.3 `resolve_goal` 지원을 확인한다. Qwen만 허용하는 구버전 앱은 새 API에서 업로드를 차단하므로 worker·API·앱을 함께 배포한다. 새 앱도 Qwen API를 거절한다. 롤백은 세 구성요소와 endpoint 설정을 함께 되돌린다.
3. 앱을 빌드·설치한다. 앱의 API 기본 주소는 `https://click-db.onrender.com`이고 `CLICK_API_BASE_URL` Gradle 설정이 우선한다. 모델 서버 URL이나 비밀은 앱에 넣지 않는다.
4. 키워드 요청, 우회 표현, 부정 요청, 특정 앱 글자 변경, 미지원 요청, 추론 서버 중단, 취소 후 늦게 도착한 응답을 실제 휴대폰에서 확인한다. 이미 전송한 음성은 클라이언트 취소만으로 회수할 수 없다.
5. 공개 한국어 평가와 실제 명령 평가는 [품질 평가 기준](../../docs/ai-quality-evaluation.md)에 따라 별도로 시행한다. 단위 테스트 통과를 모델 정확도 통과로 기록하지 않는다.

2026-10-27: Qwen STT worker·응답 계약·앱 검증을 Whisper로 전환했다. 실제 가중치 배치·추론 서버 배포·휴대폰 검증은 별도 단계다. 모델 정확도 향상을 검증한 변경으로 해석하지 않는다. 목표 분류의 self-hosted/Jev 설정과는 독립적이다.

구현 근거: [faster-whisper v1.2.1 실행 API](https://github.com/SYSTRAN/faster-whisper/blob/v1.2.1/README.md), [로컬 모델·segment API](https://github.com/SYSTRAN/faster-whisper/blob/v1.2.1/faster_whisper/transcribe.py), [large-v3 변환본](https://huggingface.co/Systran/faster-whisper-large-v3). 음성은 우리 worker에서 처리하며 OpenAI API 키를 사용하지 않는다.
