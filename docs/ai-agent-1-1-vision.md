# AI-Agent 1-1: On-device Vision/OCR fallback

AI-Agent 1-1은 독립 planner나 범용 화면 이해 모델이 아니라 AI-Agent 1의 텍스트 기반
화면 관측 fallback이다. Accessibility Tree가 비었거나 target matching이 반복 실패한
경우에만 실행하고, 성공하면 기존 `Normalize → Classify → Match → Safety → Guide` 경로로
복귀한다. 아이콘만 있는 화면, 게임/Canvas, 복잡한 이미지 이해에는 이 기능을 사용하지
않는다.

## 구현 파이프라인

1. FE가 Accessibility Tree와 Task Graph 기반 matching을 우선 사용한다.
2. 빈 Tree 또는 반복된 `target_not_found`/`ambiguous_target`에서 사용자에게 이 요청 동안의
   화면 분석 동의를 요청한다. 원본 이미지는 기기 밖으로 나가지 않지만 인식된 글자와
   위치는 Click 서버로 전송된다는 점을 구분해 설명한다.
3. Android 11(API 30)+의 `AccessibilityService.takeScreenshot()`으로 현재 display를
   한 번 캡처한다.
4. 촬영 전 Accessibility 정보로 비밀번호·결제·금융 화면을 차단한다. Tree가 비어 있던
   경우에는 로컬 OCR 직후 같은 정책을 다시 적용하며, 민감 결과는 즉시 폐기한다. 알림
   내용이나 입력 예측어가 섞일 수 있는 System UI·키보드 화면도 차단한다.
5. 개인정보 의심 node 영역을 bitmap에서 검게 마스킹한다. 일반 설정 화면의 계정 카드는
   전체 화면을 막지 않고 OCR block 전체를 버려 이름과 이메일이 함께 빠지게 한다.
6. 번들된 ML Kit Korean Text Recognition v2 모델로 기기 안에서 OCR을 수행한다.
7. 이메일·전화번호·긴 식별번호가 포함된 OCR line을 제거하고, 신뢰도 0.65 미만의
   결과를 버린다.
8. OCR box를 실제 screen 좌표로 변환하고 Accessibility node와 결합해 role 및 클릭
   가능한 상위 영역을 복원한다.
9. FE는 `frontend_ocr`의 text, confidence, bounds만 전송한다. 중복 전체 `ocr_text`는
   전송하지 않고 element 200개, element text 200자로 제한한다. screenshot byte를
   직렬화하거나 파일·로그에 저장하는 코드는 없다.
10. AI1은 Task Graph 의미 점수와 OCR 신뢰도의 최솟값으로 후보를 평가하고, 기준 미달
    또는 top-2 ambiguity이면 선택하지 않는다.
11. 선택된 bounds는 기존 Guide Overlay로 전달된다. OCR에서 찾은 항목임을 알리고,
    사용자가 표시가 맞을 때만 누르고 틀리면 다시 찾아달라고 말하도록 안내한다.
12. 동의를 거절하거나 OCR이 실패해도 작업 전체를 강제 취소하지 않는다. Task Graph의
    다음 단계 문장을 좌표 없이 제공해 사용자가 직접 이동하거나 다른 요청을 할 수 있다.

## 기술 선택 검토

- ML Kit의 번들 한국어 모델을 선택했다. 한국어/라틴 UI text와 bounding box가 핵심인
  현재 Android 설정 task에 맞고, 첫 실행 모델 다운로드 실패가 없으며 이미지 외부
  전송도 없다. 사용하지 않는 Latin 전용 artifact는 넣지 않으며 recognizer를 서비스 수명
  동안 재사용하고 transient bitmap을 제자리 마스킹해 warm latency와 peak memory를 줄인다.
  앱 크기 증가는 이 결정의 명시적인 비용이다.
- 외부 VLM은 원본 이미지 유출, 지연, 비용, 비결정성 때문에 사용하지 않는다. Backend도
  `screenshot_base64`를 422로 거절한다.
- YOLOv8은 이번 버전에 넣지 않는다. OCR로 읽을 수 없는 아이콘 실패 사례, 라벨 품질,
  기기별 holdout set이 없는 상태에서 학습하면 정확도 주장을 검증할 수 없다. 실제
  non-text target 실패가 100개 이상 모이고 OCR+Accessibility 성공률이 목표 이하일 때만
  경량 detector를 비교 평가한다. 도입 시 조건은 holdout mAP, target success 개선,
  Android 중급 기기 p95 latency, APK/model 크기, TFLite/ONNX 호환성이다.
- API 26~29에서 MediaProjection은 Activity 기반 시스템 동의를 반복 요구하고 전체 화면
  캡처 권한 범위를 넓힌다. 자동 fallback으로 추가하지 않고 안전하게 지원 불가로
  종료한다. 제품 요구가 생기면 별도의 명시적 권한 UX로만 도입한다.

## 페르소나 검토와 제품 판단

- 고령·저문해 사용자: 기술 용어 대신 왜 필요한지, 무엇이 서버로 가는지, 거절해도
  계속할 수 있는 방법을 한 화면에서 제공한다. 큰 글씨에서 잘리지 않도록 동의창은 내용
  높이에 맞춘다.
- TalkBack·저시력 사용자: 시각 highlight만 정답으로 간주하지 않는다. 동의창을 접근성
  announcement와 focus 대상으로 만들고, 목표 이름과 수정 방법을 음성 문장에도 포함한다.
- 바쁜 사용자: 평소에는 Accessibility 경로만 사용한다. 두 번 찾지 못했을 때만 OCR을
  제안하고 분석 중 상태를 말해 불필요한 지연과 멈춘 듯한 느낌을 줄인다.
- 개인정보 민감 사용자·보호자: 이미지의 로컬 처리와 OCR 파생정보의 서버 전송을 따로
  설명하고, 세션이 아니라 현재 요청 범위의 동의임을 밝힌다. 거절은 사용자 중단으로
  기록하지 않는다.
- ML·QA 담당자: confidence가 없는 OCR 결과는 임의의 0.75로 보정하지 않고 포기한다.
  Task Graph 점수, OCR confidence, top-2 margin 중 하나라도 불충분하면 추측하지 않는다.

결론은 기능 유지다. 단, "화면을 이해하는 AI"로 노출하지 않고 텍스트가 보이는 설정형
UI에서 Accessibility 실패를 보완하는 선택적 기능으로만 사용한다. 아이콘/이미지 문제는
OCR 호출을 늘려 해결하지 않고 별도 실패 데이터가 충분해진 뒤 detector 필요성을 재평가한다.

## 종료 조건

- 후보 발견: 좌표를 기존 AI1 matcher/overlay로 반환하고 1-1 종료
- 동의 거절, 민감 화면, screenshot/OCR 실패, 무신호: 조작을 발행하지 않고 Vision만
  종료한 뒤 좌표 없는 수동 안내로 복귀
- stale observation: 결과를 폐기하고 새 Accessibility observation부터 다시 시작
- 후보 저신뢰·모호성: fresh observation 또는 사용자 확인으로 종료
- 세션 취소·교체·만료: 결과를 폐기하고 종료

## 검증

- JVM unit: consent, raw image 부재, 민감 화면 차단, 좌표 scaling, 저신뢰 필터,
  Accessibility 결합
- Python runtime: provider/redaction 경계, raw image 차단, confidence gating,
  Vision synthetic node부터 Guide bounds까지
- Android instrumentation: 720p/1080p/1440p, light/dark, 글자 크기 변화에서 실제 번들
  OCR target recall과 평균 처리 시간을 `ClickVisionEval`에 기록
- Host benchmark: `python scripts/evaluate_vision_fallback.py --output
  data/runtime/vision_evaluation.json`

실기기 OCR 정확도와 처리 시간은 Android 11+ 기기에서 instrumentation test를 실행한
결과만 성능 수치로 인정한다. Host benchmark는 OCR 이후 계약과 matching 성능만 측정한다.
