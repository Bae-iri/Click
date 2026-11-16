# 목표 해석 LLM 연결

음성 인식은 앱의 Android 온디바이스 서비스에서 처리하고 Backend에는 텍스트만 전달한다.

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

화면 판단 모델 설정은 [Jev 안내](jev.md)와 [화면 관측 계약](jev-screen.md)을 참고한다.
