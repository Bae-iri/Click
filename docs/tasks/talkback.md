# TalkBack 켜기·끄기: 백엔드 계약

| Goal Type | slots |
| --- | --- |
| `settings.talkback.enable` | `{"setting":"talkback","state":"on"}` |
| `settings.talkback.disable` | `{"setting":"talkback","state":"off"}` |

`POST /api/v1/agent/commands`, `target_app=android_settings`, catalog 2.0.0 또는 2.1.0을 사용한다. slots 누락·불일치·추가 입력과 구 catalog 요청은 거부한다. 켜기와 끄기는 별도 Goal이며 현재 상태를 보지 않고 무조건 스위치를 반전시키는 동작은 포함하지 않는다.

실제 기기용 승인 그래프가 없으므로 HTTP 422 `INVALID_REQUEST`로 종료하고 Agent를 호출하지 않는다. catalog는 `catalog_only`로 유지한다. 합성 그래프 테스트는 등록·선택·Agent 전달 계약만 검증한다.

실행 연동 시 변경 전후 TalkBack 상태와 시스템 확인 화면을 검증하고 기존 사용자 승인 계약을 적용해야 한다. TalkBack 사용 중에는 단일 탭이 초점 이동, 두 번 탭이 활성화이므로 일반 탭 가이드를 그대로 적용해서는 안 된다. 끄기 안내를 사용자가 실제로 따라갈 수 있는지, 미설치 기기·제조사별 화면·접근성 서비스 공존을 실기기에서 검증한 후 실행 상태를 승인한다.
