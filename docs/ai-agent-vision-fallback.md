# AI-Agent Accessibility Tree → Vision fallback

Agent(1)의 화면 관측 보완이며 Goal 분류·Task Graph 생성에는 사용하지 않는다. 이 문서는 FE·BE·AI1 사이의 동의 handshake, 필드, 오류 계약의 기준이다. 설계 근거, 기기 안 처리 단계, UX 기준은 [AI-Agent 1-1](ai-agent-1-1-vision.md)을 따른다.

## 1. 사용 순서와 동의 단위

AX를 우선 사용한다. 정규화 후 node가 없는 화면, 또는 graph의 다음 target을 찾지 못했고 이미 한 번 이상 복구를 시도한 화면에서 `confirmation` + `render_policy.vision_fallback`을 반환한다. `ambiguous_target`에서는 Vision을 요청하지 않는다. 설정 앱을 열어야 하는 launcher 복구 경로와 이미 실패한 target의 재시도 예산은 유지한다. 동의를 기다리는 동안 실패 횟수를 소비하지 않는다.

1. **FE → BE:** 이미지 없이 현재 AX 관측을 보낸다. 읽을 수 없으면 `nodes: []`와 package·window·화면 크기를 보낸다.
2. **AI1 → BE → FE:** 분석이 필요하면 BE가 `metadata.confirmation`에 `purpose: vision`, `scope: single_analysis`, 일회용 `id`, `version`, `expires_at`(UTC 초, 발급 후 120초)을 발급한다.
3. **FE:** 매번 캡처 **이전**에 “현재 화면을 한 번 캡처해 기기에서 글자를 읽고, 글자와 위치를 서버에 보내도 될까요? 원본 이미지는 전송하지 않아요.”를 표시한다. 세션 동의 저장, 과거 `user_consented=true` 재사용, 자동 재캡처는 없다. 확인 대기 중 자동 observe 전송도 멈춘다.
4. **허용:** 확인 overlay를 닫고 가이드와 Click UI를 숨긴 뒤 같은 화면인지 새 AX로 검사한다. 비밀번호·입력 필드가 보이는 화면, 결제·금융, System UI·키보드 화면이면 캡처하지 않는다. Android screenshot을 한 번 수집하고 개인정보 의심 영역을 가린 뒤 기기 내 ML Kit 한국어 OCR을 수행한다. 현재 graph의 설정 메뉴 vocabulary에 해당하는 글자와 실제 OCR bounds만 전송한다. 처리 단계 상세는 [AI-Agent 1-1](ai-agent-1-1-vision.md#기기-안-처리-단계)에 있다.
5. **FE → BE:** `respond_confirmation`에 `accepted`, 동의 ID, 현재 session/episode/version, 함께 수집한 AX와 `vision_snapshot`을 담는다.
6. **BE:** owner·session·episode·version·동의 ID·만료·원래 AX 화면 fingerprint·snapshot 문맥·좌표를 검사한다. 통과한 요청 ID만 내부 `consent_request_id`로 AI1에 전달한다. FE가 이 내부 필드를 보내면 422다.
7. **AI1:** 허용된 OCR을 개인정보 마스킹 후 `vision:*` node로 바꿔 기존 Normalize → Classify → Match → Guide 경로에 합류시킨다. window가 명시된 AX와 Vision은 같은 window여야 한다. 정상 AX만으로 target을 찾으면 Vision은 분석하지 않는다.
8. **BE:** turn commit과 함께 기존 동의를 소비한다. 다음 분석은 새 동의가 필요하다. 동일한 `request_id`와 동일 본문의 HTTP 재전송은 저장된 응답을 반환하며 재분석하지 않는다.

거절 시 이미지·OCR 없이 `respond_confirmation/rejected`를 보내고 안내를 중단한다. 만료, 화면 변경, OCR/캡처 실패, pause/cancel/replace, 서비스 종료는 캡처 예약·전송 대기를 폐기한다. 실행 중 취소된 OCR의 결과도 전송하지 않는다. 실패 시 자동으로 재캡처하지 않으며 재개/새 요청에서 다시 확인한다. 분석 결과가 없으면 정보 부족을 안내하고 원본 이미지 전송으로 자동 전환하지 않는다.

**Vision 동의와 설정 변경 승인은 별개다.** Vision 승인으로 `tap`/`drag` 승인 또는 완료 판정을 만들지 않는다. OCR만으로 slider 역할·현재값·변경 성공을 추정하지 않는다. 현재 FE는 메뉴 글자 위치 안내까지만 보완하며, 설정 변경·완료 검증에는 기존 AX control/value 증거가 필요하다.

## 2. FE·BE가 주고받는 필드

| 방향 | 필드 | 의미 |
| --- | --- | --- |
| BE → FE | `guide_command.command_type=confirmation`, `action_type=observe_only` | 캡처 전 확인 화면 |
| BE → FE | `render_policy.vision_fallback` | `requires_user_consent=true`, `consent_scope=single_analysis`, `processing=on_device_ocr`, `raw_screenshot_allowed=false`, 허용 payload 목록, 현재 graph의 `vocabulary_key` |
| BE → FE | `metadata.confirmation.id/purpose/scope/version/expires_at` | 요청별 동의 challenge; 설정 변경 확인과 purpose로 구분 |
| FE → BE | `session_id`, `episode_id`, `expected_version`, `request_id` | 세션/실행 식별, 동시 요청·재전송 통제 |
| FE → BE | `command_type=respond_confirmation`, `user_confirmation`, `confirmation_id` | 명시적인 사용자 응답 |
| FE → BE | `accessibility_tree` | 새 AX. node를 못 읽어도 package·화면 크기·window·observation ID 유지 |
| FE → BE | `vision_snapshot.user_consented=true` | 사용자 응답의 보조 표시. 이것만으로는 승인되지 않음 |
| FE → BE | `observation_id`, `captured_at_ms`, `package_name`, `active_window_id`, `screen_width`, `screen_height` | OCR의 출처·시각·좌표 공간. observation/package/window/크기는 동봉 AX와 일치 |
| FE → BE | AX와 `vision_snapshot`의 `screen_revision` | 선택 필드(이전 클라이언트 호환). 현재 FE는 서비스 epoch + 외부 앱 변화 카운터를 항상 전송한다. 두 payload 및 동의 당시 AX revision과 일치해야 함 |
| FE → BE | `provider`, `redaction_applied`, `elements` | `provider=frontend_ocr`, FE 마스킹 표시, 메뉴 text·role·confidence·실제 bounds. confidence가 없거나 0.65 미만인 element는 FE와 AI1 모두 버린다 |
| FE → BE | `processing_time_ms` | 선택. 기기 OCR 처리 시간(0~60000). 품질 지표에만 사용 |
| BE → AI1 내부 | `consent_request_id` | BE 검증을 통과한 이 요청에만 유효. FE 입력이나 checkpoint 동의 저장에 사용 금지 |

좌표는 전체 화면의 물리 px `[left, top, right, bottom]`이다. dp, overlay 상대좌표, 임의 확대 bounds를 보내지 않는다. 현재 FE는 회전·화면 크기가 캡처 도중 바뀌면 결과를 폐기한다. `ocr_text`는 계약상 허용하지만 현재 FE는 자유 텍스트를 보내지 않고 정해진 설정 메뉴 `elements`만 보낸다. 원본 screenshot, OCR 전문, 사용자 동의 값을 로그에 저장하지 않는다.

메뉴 어휘의 단일 원본은 `AI-Agent/goal_catalog/vision_vocabulary.json`이다. Android가
동일 파일을 asset으로 포함한다. FE는 서버의 `vocabulary_key`로 현재 목표의 유한한
허용 목록을 선택하고, AI1은 클라이언트 key를 신뢰하지 않고 활성 graph의
`required_slots.setting`으로 다시 제한한다. 알 수 없는 key는 FE에서 캡처 전에
`unsupported`, AI1에서 쓸 수 있는 글자 없음으로 처리한다. 어휘 목록은 graph 실행
승인을 대신하지 않으며 SSID·블루투스 기기 이름·계정·입력값을 포함하지 않는다.

최초 요청은 기존 `start_task` 계약을 따른다. 허용 응답의 예시는 다음과 같다. ID·버전·시각은 실제 응답과 수집 값으로 교체한다.

```json
{
  "contract_version": "agent-fe-contract.v0.2",
  "command_type": "respond_confirmation",
  "request_id": "vision-use-request-uuid",
  "session_id": "session-uuid",
  "episode_id": "episode-uuid",
  "expected_version": 1,
  "confirmation_id": "00000000-0000-4000-8000-000000000001",
  "user_confirmation": "accepted",
  "accessibility_tree": {
    "package_name": "com.android.settings",
    "active_window_id": 42,
    "screen_width": 1080,
    "screen_height": 2400,
    "observation_id": "capture-uuid",
    "captured_at_ms": 1789888322000,
    "nodes": []
  },
  "vision_snapshot": {
    "user_consented": true,
    "redaction_applied": true,
    "provider": "frontend_ocr",
    "package_name": "com.android.settings",
    "active_window_id": 42,
    "screen_width": 1080,
    "screen_height": 2400,
    "observation_id": "capture-uuid",
    "captured_at_ms": 1789888322000,
    "processing_time_ms": 420,
    "elements": [
      {"element_id": "ocr_0", "text": "설정", "bounds_in_screen": [24, 40, 500, 96], "role": "text", "confidence": 0.97, "actionable": false},
      {"element_id": "ocr_1", "text": "디스플레이", "bounds_in_screen": [24, 320, 1056, 408], "role": "text", "confidence": 0.93, "actionable": true}
    ]
  }
}
```

새 요청 ID로 이전 동의 재사용, 만료, 다른 앱/window/화면/크기, 캡처 전 timestamp, 화면 밖 bounds, 허용 없는 Vision은 `409 CONFIRMATION_INVALID`다. owner·episode·version 위반은 기존 인증/세션 오류를 따른다. 원본 `screenshot_base64`가 있으면 동의했더라도 거절한다. 기존 `/agent/turn`도 같은 BE 검증을 적용하므로 boolean만 보내던 클라이언트는 이 handshake로 변경해야 한다.

## 3. Android 권한과 구현 범위

[MediaProjection](https://developer.android.com/media/grow/media-projection)은 캡처 세션마다 OS 동의가 필요하다. [AccessibilityService.takeScreenshot](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))는 API 30 이상과 서비스의 `canTakeScreenshot` capability를 요구한다. 모든 Vision API가 매 분석마다 OS 팝업을 띄우는 것은 아니다. **Click은 API 종류와 별도로 매 분석 1회 앱 내 확인을 요구하는 제품 정책**을 적용한다. 접근성 서비스 활성화는 이 확인을 대신하지 않는다.

현재 구현은 Android 11 이상, `com.android.settings` / `com.samsung.android.settings`, 기본 display, 화면 크기가 일치하는 전체 화면 캡처를 지원한다. [ML Kit 한국어 OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)의 번들 모델을 사용한다. Android 8~10, 캡처 권한 미반영, 보호된 화면, 다른 앱은 오류 안내 후 중단한다. 접근성 서비스 설정 변경은 설치 업데이트 후 서비스를 다시 켜야 반영될 수 있다.

이미지는 기기 메모리에서 OCR 후 폐기한다. API key나 외부 VLM credential은 필요하지 않다. 외부 VLM은 사용하지 않으며 AI1의 VLM 경로는 `not_configured`를 반환한다. 판단 근거와 재검토 조건은 [AI-Agent 1-1](ai-agent-1-1-vision.md#기술-선택-검토)에 있다.

## 4. 개인정보 마스킹과 실패 대응

**이미지 → 기기 내 OCR → 허용 텍스트만 전송 → BE 재검사** 순서로 처리한다. FE의 `redaction_applied`는 보조 정보이며 서버 검사를 대체하지 않는다.

- 캡처 전 `VisionSafetyPolicy`가 보이는 비밀번호·입력 필드, 결제·금융 패키지와 문구, System UI·키보드를 확인하면 캡처하지 않고 `masking_failed`로 보고한다. OCR 직후에도 인식 글자에 같은 문구 검사를 적용한다.
- AX 수집 단계에서 `isPassword`, `isEditable`, text-field를 기록한다. 이 node의 text/content description/value를 직렬화하지 않고, clickable/enabled도 비활성화한다. 캡처 전에 필드의 유효 bounds를 검증하고 이미지에서 해당 영역을 검게 가린다. 알려진 민감 필드인데 bounds가 없거나 유효하지 않으면 OCR을 시작하지 않는다.
- AX가 아예 없으면 민감 영역을 완전히 식별했다고 가정하지 않는다. 원본은 기기 메모리의 로컬 OCR에만 사용하고 이미지 전송은 차단한다. 전송 단계는 유한한 메뉴 허용 목록에 정확히 일치하는 글자만 통과시켜 보호한다. 외부 이미지 전송을 허용할 만큼 완전한 이미지 마스킹을 구현했다는 의미는 아니다.
- OCR은 메뉴 허용 목록 밖의 **전체 line을 삭제**한다. 이름·주소·이메일·전화번호·계좌/카드번호·주민번호·인증번호를 정규식만으로 놓치는 경우도 전송하지 않는다. `디스플레이 김철수`를 `디스플레이`로 바꾸어 잘못된 actionable target을 만들지 않는다. 해당 line 전체를 버린다.
- BE/AI1도 `VISION_LABELS`로 같은 제한을 재적용한다. provider는 고정 문자열, element ID는 `ocr_N`으로 다시 생성해 임의 식별자에 섞인 개인정보가 guide/trace에 반영되지 않게 한다. 자유 OCR은 허용된 line만 남긴다. 전체 삭제는 `no_signal`이며 마스킹 성공 여부를 추측하지 않는다.
- bitmap 마스킹, bounds 검증, OCR 결과의 개인정보 검사 또는 서버 sanitizer가 실패하면 모든 Vision 결과를 폐기한다. 에러에 포함된 원문·이미지·provider 예외 메시지를 로그나 HTTP 응답에 넣지 않는다. 마스킹된 이미지조차 외부로 보내지 않는다.

| 상황 | FE/BE 동작 | 다음 단계 |
| --- | --- | --- |
| 사용자 거절 | `rejected`, Vision payload 없음 | 안내 중단 |
| 민감 화면(캡처 전 또는 OCR 직후)·민감 영역 bounds 불명·마스크 적용 오류·전송 텍스트 검사 예외 | FE에서 bitmap/결과 폐기, `rejected` + `vision_failure=masking_failed`만 전송 | BE가 BLOCKED와 개인정보 보호 안내 반환 |
| FE가 마스킹 완료를 표시하지 않음 / 서버 sanitizer 예외 | AI1이 payload를 비우고 `masking_failed`, 조작 target 없음 | 동의 소비, BLOCKED |
| 개인정보/미등록 글자를 제거한 결과가 없음 | `no_signal` | 재캡처하지 않고 관측 부족 안내 |
| 캡처/인식 실패, 화면 변경, 미지원 | 각각 `capture_failed`, `ocr_failed`, `screen_changed`, `unsupported` | 원본 첨부 없이 중단 |
| 재분석 요청 | 새 AX → 새 동의 ID → 사용자 허용 | 이전 동의 재사용 금지 |

실패 보고는 기존 `respond_confirmation`에서 `user_confirmation=rejected`, `vision_failure` enum만 추가한다. `vision_snapshot`과 실패 코드를 동시에 보내거나 `accepted`와 실패 코드를 보내면 422다. 정상 응답의 `metadata.vision_failure`에는 FE 실패 코드만, `metadata.debug.vision_fallback.status`에는 서버의 `masking_failed`/`no_signal`만 남긴다. 이는 DB에 이미지·OCR 원문을 저장하는 실패 보고가 아니다.

## 5. 구현 파일과 검증

- AI1: `runtime/engine.py`, `runtime/guide.py`, `runtime/privacy.py`, `runtime/vision.py`.
- BE: `services/safety.py`, `services/agent_turn.py`, `clients/runtime_agent.py`, `schemas/agent_turn.py`. 기존 `safety_confirmations.command` JSON의 purpose를 이용해 추가 DB migration 없이 한 세션의 현재 확인을 관리한다.
- FE: `ClickOverlayService`(동의 UI), `ClickAccessibilityService`(OCR 엔진 수명), `OnDeviceVision`(캡처·입력 필드 마스킹·허용 목록), `VisionOcrEngine`(개인정보 마스킹·OCR·block 삭제), `VisionSafetyPolicy`, `VisionElementMapper`(좌표·AX 결합), `VisionPrivacy`, `AgentVisionSnapshot`, 접근성 서비스 XML, ML Kit 의존성.
- 회귀: `AI-Agent/tests/test_vision_fallback.py`, `Backend/tests/test_vision_consent.py`에서 요청별 동의, 만료·재사용·다른 owner/window, 원본 이미지 거절, 정상 OCR 안내, 변경 승인 분리, HTTP 멱등성을 검증한다.
- 기기 QA: 허용/거절, 허용 대기 중 화면 이동, 캡처 중 회전·취소, OCR 실패, API 29 미지원, API 30+ 캡처, 서비스 권한 재활성화, 확인 UI가 캡처되지 않는지, 개인정보 메뉴 외 텍스트 미전송을 확인한다.

검증 환경: AI1 Python 테스트, BE vision·STT 관련 테스트, FE Gradle 앱·androidTest 컴파일과 `VisionFallbackTest`·`VisionPrivacyTest` JUnit 테스트를 실행한다. 실제 screenshot/OCR 기기 실행은 아직 검증하지 못했다. 설치 전 위 기기 QA가 필요하다.

## 3사이클 재시도

현재 분석은 최대 3회 실패까지 이어진다. 매 사이클에는 새 캡처와 새 동의 challenge가
필요하다. 첫 두 번의 캡처/OCR 실패 또는 no_signal은 새 confirmation을 반환하고,
3회 실패는 Agent 1 수동 안내와 사용자 재확인 대기로 반환한다. 원래의 동일 요청
재전송은 저장된 응답을 반환하므로 실패 횟수를 중복 증가시키지 않는다.

FE 실패 보고(rejected + vision_failure)도 현재 vision challenge ID와 version 검증을
통과해야 재시도에 반영된다. 실제 사용자 거절은 declined, screen_changed는 횟수 증가
없이 사용자 재확인 대기, masking_failed/unsupported는 즉시 종료한다.
`render_policy.vision_cycle`에 실패 횟수·실패 원인·AX 빈 여부가 있고, confirmation의
vision_fallback에는 cycle_number/strategy/retry_delay_ms/focus_bounds가 포함된다.
인식 실패와 목표 미발견은 별도 원인이며 LLM은 not_configured 연결 지점만 존재한다.
