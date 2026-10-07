# 앱 삭제·앱 권한 관리·저장 공간 정리

| Goal Type | slots |
| --- | --- |
| `settings.apps.uninstall` | `{"setting":"apps","action":"uninstall"}` |
| `settings.app_permissions.manage` | `{"setting":"app_permissions","action":"manage"}` |
| `settings.storage.cleanup` | `{"setting":"storage","action":"cleanup"}` |

`POST /api/v1/agent/commands`, `target_app=android_settings`, catalog 2.0.0 또는 2.1.0을 사용한다. 이번 변경은 기존 앱 관리 계약에 저장 공간 정리 계약을 추가하고 동일한 테스트 묶음으로 검증한다. slots의 누락·불일치·추가 입력과 구 catalog를 거부한다.

현재는 작업 의도만 받는다. 대상 앱·권한·파일 경로·전체 삭제·앱 데이터 초기화 등 추가 slots는 받지 않는다. 실제 기기용 승인 그래프가 없으므로 HTTP 422 `INVALID_REQUEST`를 반환하고 Agent를 호출하지 않는다. 합성 그래프는 등록·선택·전달 경계만 검증한다.

실행 확장 시 삭제 대상 및 결과를 명시적으로 확인하고 최종 삭제 전에 기존 사용자 승인 계약을 적용해야 한다. 저장 공간 정리의 완료는 실제 공간 변화 또는 선택 항목 삭제 관측으로 검증해야 하며 설정 화면 도착만으로 완료를 선언하지 않는다. 검증된 그래프를 등록하기 전까지 catalog는 `catalog_only`로 유지한다.
