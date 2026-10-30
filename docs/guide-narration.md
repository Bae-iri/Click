# 확정된 행동을 음성 대본으로 표현하기

## 실행 경로

`task graph → 기존 안전성/상태 검증 → 중립 응답 저장 → 개인화 → 대본 표현 → 기존 Android TTS`

LLM은 작업 선택, 대상 선택, 위치, 행동, 방향, 승인, 완료 판정에 관여하지 않는다. HTTP 응답의 `guide_command.message`만 복사본에서 바꾸므로 API 계약과 task graph는 그대로다. 저장되는 checkpoint/재시도 원본에도 생성 문구를 넣지 않는다.

외부로 내보내는 입력은 승인된 공개 메뉴 이름·행동·방향·허용 표현 목록뿐이다. 전체 화면, OCR, 사용자 요청, 사용자 식별자, 세션, 좌표, 개인정보는 모델에 전달하지 않는다. 공개 응답에서 제거된 target_label은 해당 그래프의 현재 전이 ID·시작 상태·행동·원문 안내가 모두 일치할 때만 그래프의 고정 메뉴 어휘로 복원한다. 알 수 없는 메뉴나 변경된 원문은 기존 문구를 사용한다.

실시간 문구는 자유 생성 대신 **검증 가능한 표현 후보를 LLM이 선택·조합**하게 했다. 대상·방향 누락, 다른 행동 추가, 잘못된 완료 선언을 문자열 검사로 확실히 거부하기 위한 초기 범위다. 예:

- 확정 입력: 디스플레이 / tap / 글자 크기 작업
- 출력: “글자 크기를 바꾸러 가볼게요. 화면에서 디스플레이 항목을 눌러주세요.”

완료·차단·결과 확인·승인 요청·복구·비전 확인 문구는 바꾸지 않는다. 이미 개인화된 설명도 보존한다. 승인된 뒤에 나온 슬라이더 조작 안내는 원래 방향에 맞는 표현만 선택한다.

## 설정

Backend 환경에 아래를 설정하고 서버를 재시작한다. Docker 개발 구성에도 전달 경로를 연결했다.

```dotenv
GUIDE_NARRATION_ENABLED=true
SELF_HOSTED_LLM_URL=https://your-private-worker/v1/chat/completions
SELF_HOSTED_LLM_TOKEN=your-private-token
SELF_HOSTED_LLM_MODEL=your-deployed-model
```

기존 요청 분류와 같은 자체 호스팅 엔드포인트 정책을 재사용한다. Jev나 외부 API로 대체 호출하지 않는다. Bedrock 신규 연동을 추가한 변경은 아니다.

기본은 OFF. 설정 누락, 실패, 잘못된 JSON, 도구 호출, 잘린 응답, 허용되지 않는 문장, 800ms 초과 시 기존 안내문을 그대로 반환한다. 별도 daemon 작업과 동시 실행 2개 상한으로 느린 모델이 요청을 무한정 막지 못하게 했다. 늦게 도착한 대본이 화면에 다시 적용되는 경로도 없다.

## 튜토리얼

`Frontend/app/src/main/assets/tutorial_narration.json`에 저장된 검토 대본만 앱에서 사용한다. 초기 소개·버튼 누르기·연습 완료 3개는 코딩 어시스턴트가 오프라인 작성 후 원문 의미와 행동 보존을 검토해 저장했다. 사람 검토나 운영 모델 호출로 생성한 결과라고 표시하지 않는다.

앱은 `approved=true`, 비어 있지 않은 `reviewed_by`, 정확히 일치하는 source를 요구한다. source가 바뀌거나 파일이 없거나 손상되면 기존 대본을 읽는다. 튜토리얼 실행 중에는 모델을 호출하지 않는다.

다음 명령은 자체 호스팅 모델로 새 초안 파일만 생성한다.

```sh
cd Backend
uv run python scripts/generate_tutorial_narration.py \
  --env-file .env \
  --source ../Frontend/app/src/main/assets/tutorial_narration.json \
  --output /tmp/tutorial-narration-draft.json
```

초안은 전부 미승인이다. 버튼 이름, 동작, 방향, 연습 여부, 완료 여부를 원문과 대조해 검토한 뒤 `approved`/`reviewed_by`를 기입하고 버전 관리되는 asset에 반영한다. 생성기는 기존 파일을 덮어쓰지 않는다.

## 검증

모델 응답 모킹으로 문구만 변경되는지, 잘못된 대상·방향·완료 문구·지연·중복 JSON·설정 누락 시 기존 안내가 유지되는지 검사한다. 실제 HTTP 작업 응답과 재시도 경로도 검증한다. 튜토리얼 asset 로드와 원문 변경 시 복귀는 Android 테스트로 검증한다.

검증 결과: Android 전체 단위 테스트 197개와 디버그 APK 빌드 통과. Backend 전체 회귀 테스트 1,011개 통과 후, 마지막 경고 보존·슬라이더 안내 검증을 포함한 대본 관련 테스트 25개도 통과. 로컬 자체 호스팅 LLM 설정은 없어 실모델 호출·지연·음성 실기기 검증은 아직 수행하지 않았다.

원격 develop 병합 후 튜토리얼 원문은 `Frontend/app/src/main/res/values/tutorial_speech.xml`에서 화면 문구와 독립적으로 관리한다. 검토 대본 조회에는 이 음성 리소스의 원문을 사용한다. 저장된 source가 일치하지 않으면 최신 음성 리소스를 그대로 읽어 원격의 대본 수정을 보존한다. 새 초안을 만들 때에는 현재 음성 리소스와 asset source를 먼저 맞춘다.
