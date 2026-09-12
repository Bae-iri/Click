# Error Handling

**Week 4:** runtime timeout returns 504 / AGENT_TIMEOUT; occupied execution slots return 503 / SERVICE_UNAVAILABLE. See [retry and capacity policy](week4.md).

**2주 차 갱신:** 인증·공통 오류 envelope·body 제한·서버 생성 trace ID는 구현했다. 현재 동작은 [week2.md](week2.md)의 공통 오류 절을 따른다. 아래 표는 1주 차 기준의 기존 동작과 최종 설계 방향이다. validation 입력 원문은 이제 오류 응답에 포함하지 않는다.

## 현재 HTTP 동작

| 상태 | 사례 | 클라이언트 처리 |
|---|---|---|
| 403 | 개인화 동의 없이 이벤트 제출 | 동의 상태 확인. 자동 재시도하지 않음 |
| 404 | 사용자 없음 / 목표 없이 알 수 없는 세션 요청 | 사용자 상태 확인 또는 새 목표로 새 세션 시작 |
| 409 | 동일 request ID를 다른 세션·사용자가 재사용 | 잘못된 ID 재사용 수정 |
| 422 | DTO 형식 오류, 관찰 누락, ValueError | 입력 수정. 반복 전송하지 않음 |
| 503 | ready의 DB 연결 실패·테이블 누락 | 서버 준비·migration 확인 |

기존 API 오류는 FastAPI의 `{"detail": ...}` 형식이다. validation detail에는 입력값이 포함될 수 있으므로 이 응답을 원문 그대로 로그·분석 서비스에 저장하면 안 된다. 이번 작업에서 오류 envelope를 일괄 변경하지 않았다.

`task_status: BLOCKED`와 confirmation은 HTTP 오류가 아니라 정상적인 업무 결과이므로 200 안에서 처리한다. 미지원·모호한 목표는 runtime 상황에 따라 설명 guide를 돌려줄 수 있다. 모든 미지원 목표가 422라고 가정하지 않는다.

## 최종 서버 정책안

| 상황 | 구현할 정책 | 인수 기준 |
|---|---|---|
| 인증 실패·타인 세션 | 401/403 또는 정보 노출을 줄인 404 | 타인 데이터·세션 상태 변경 없음 |
| 같은 ID·다른 본문 | 409 + 안정적인 오류 코드 | 동시 재전송에서도 동일 |
| payload 초과 | 413, body·node 수 제한 | 읽기 단계부터 제한하고 AI 미호출 |
| AI 지연·외부 장애 | 전체 시간 예산, 제한된 fallback, 실패 코드 | 무한 재시도 없음 |
| 서버 내부 오류 | 500 + 일반 메시지·서버 생성 추적 ID | stack·DB URL·화면·목표 원문 미노출 |
| 오래된 화면·종료 세션 | 새 관찰 요구 또는 상태 충돌 | 과거 화면에 대한 안내를 재실행하지 않음 |

새 공통 오류 형식 후보는 `{"error":{"code":"...","message":"...","trace_id":"..."}}`이다. 아직 구현하지 않았으며 Android의 오류 파서와 동시에 적용한다.

네트워크 응답 유실은 같은 ID·같은 본문으로 제한된 backoff 재시도한다. 입력·권한 오류는 자동 재시도하지 않는다. 재시도 횟수와 지연 값은 전체 turn 시간 예산을 정할 때 함께 확정한다.
