# 마스킹 / 개인정보 파이프라인

**Week 4 implemented:** input-field removal, known-pattern text redaction, catalog-only external text, disabled raw-image egress, trace/event allowlists and cleanup. See [current limits](week4.md). This does not claim universal PII detection or completed consent/deletion integration.

## 데이터 정책 초안

| 데이터 | 필요한 용도 | 전송·저장 방향 |
|---|---|---|
| 자연어 목표 | 목표 해석 | 최소 사용, 원문 DB 저장 기본 해제 |
| tree text·content_description·extras | 화면·대상 판별 | password·OTP·개인 입력값 제거 후 처리, 원본 장기 저장 금지 |
| 스크린샷·OCR | tree 실패 시 보조 | 사용자 동의 + 서버 검증된 마스킹 후 외부 처리 |
| 사용자·UI 프로필 | 안내 수준 조정 | 가명 ID, 목적별 동의·삭제 지원 |
| 행동 이벤트 | 개인화 근거 | 허용 enum·키·크기만 저장 |
| 로그·trace·오류 | 문제 추적 | 서버 생성 ID·상태·소요시간 중심, 원본 화면·본문·비밀값 제외 |

보존 기간은 아직 확정되지 않았다. 운영 저장을 도입하기 전에 데이터별 TTL·삭제 요청·백업 삭제 정책을 확정한다.

## 구현할 처리 경계

FE에서 1차 제거 → 서버 크기·스키마 검증 → 서버 민감필드 제거 → 동의 확인 → runtime/외부 provider 호출 → 응답·trace 필터링 → 허용 데이터만 저장.

클라이언트의 `redaction_applied:true`는 실제 마스킹의 증거가 아니므로 단독으로 신뢰하지 않는다. 이미지에서 마스킹을 검증·수행할 수 없는 경로는 원본 외부 전송을 허용하지 않는 방향으로 구현한다. 동의 역시 최종 서버에서는 인증 사용자와 목적·정책 버전에 연결한다.

## 현재 한계와 후속 작업

- TraceLogger는 screenshot_base64/image_base64 키를 제거하지만 일반 텍스트 개인정보를 포괄적으로 마스킹하지 않는다.
- runtime debug의 label_sample, typed_goal, 메모리 snapshot·trace에 원문이 남을 수 있다. 전체 개인정보 파이프라인 구현 완료가 아니다.
- 이벤트 metadata는 현재 자유 dict다. 키 allowlist·크기 제한·민감값 차단이 필요하다.
- 사용자 삭제는 개인화 DB 관계를 제거하지만 runtime 메모리·응답 캐시 정리까지 연결되지 않았다.
- 철회 이후 기존 응답 캐시를 반환하면 이전 개인화 문구가 유지될 수 있다. 캐시 무효화·철회 동시성 검증이 필요하다.

## 인수 기준

합성 개인정보 fixture로 tree·목표·OCR·extras·오류·trace를 검사한다. 동의 없음·마스킹 실패 시 외부 호출 0회를 확인한다. 사용자 삭제·철회 후 DB와 캐시·runtime에서 재노출되지 않아야 한다. 이 문서는 기술 설계 초안이며 실제 수집·보존 정책의 팀 확정을 대신하지 않는다.
