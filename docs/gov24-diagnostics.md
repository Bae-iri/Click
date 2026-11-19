# 정부24 안내 중단 사유

`develop`의 로그인·6개 민원 안내에 적용하는 진단입니다. 응답의 `diagnostic`과
암호화된 RuntimeEvent의 `public_guide.diagnostic`에 같은 내용을 기록합니다.
기존 보관용 `gov24-planner.v1` 실행 코드를 추가하지 않고 현재 경로에 적용합니다.

| 분류 | 대표 코드 | 판정 |
|---|---|---|
| 화면 없음·미인식 | `observation_missing`, `snapshot_missing`, `empty_accessibility_tree`, `no_visible_nodes`, `unrecognized_screen` | UNKNOWN |
| 관측 시각·앱 불일치 | `stale_observation`, `future_observation`, `package_mismatch`, `outside_gov24_app` | UNKNOWN |
| 공개 후보 부족 | `no_public_options`, `ambiguous_public_options`, `public_options_limit`, `invalid_public_option`, `no_exported_nodes`, `no_candidate_for_goal` | UNKNOWN |
| 입력 필드 | `password_field`, `sensitive_input_field`, `empty_search_field` | BLOCKED |
| 인증·동의 문구 | `certificate_prompt`, `private_identity_label`, `consent_prompt`, `external_auth_app` | BLOCKED |
| 앱·창 혼합 | `foreign_package_node`, `inactive_window_node` | BLOCKED |
| 화면 확인 한도 | `turn_limit` | BLOCKED, 실제 목표 실패로 추정하지 않음 |

각 진단에는 `code`, `category`, `stage`, 한국어 `message`, `status`, `facts`,
`version=gov24-diagnostics.v1`이 있습니다. 화면 검사 요약은 이벤트의 `observation_check`에도 기록합니다.
`facts`에는 앱 일치 여부·공개 노드 수·관측 나이·고정 사유 코드만 넣으며
원문 AX·입력값·노드 ID·좌표·미등록 앱 패키지명은 넣지 않습니다.

기존 앱의 네 가지 `handoff_reason`은 그대로 받을 수 있습니다. 세부 유형이 없으면
`native_detail_available=false`이며 비밀번호인지 검색창인지 추정하지 않습니다.
`sensitive_input_field`는 용도를 확인하지 못한 입력 필드라는 뜻입니다.
`authentication_step`은 로그인 수단 선택 후 공개 항목을 확인하지 못한 경우로,
기존 수동 진행 인계를 유지하되 `UNKNOWN`으로 보고하고 인증 화면이라고 단정하지 않습니다.

현재 공개 홈의 검증된 빈 검색창 예외와 기존 필터는 유지합니다. 그 외 입력 필드를
통과시키거나 원문을 모델에 보내지 않습니다. 새 앱은 연결된 서버 OpenAPI의
`PublicGuideScreen.privacy_reason` 지원을 확인한 뒤에만 세부 코드를 전송합니다.

실험 로그의 원인을 구분하려면 해당 서버 코드도 갱신해야 합니다. 서버만 갱신하면
기존 사유·관측 시각·앱 불일치까지, 새 앱도 설치하면 필드 유형·창 혼합까지 구분됩니다.
재접속 `snapshot`은 마지막 진단과 종료 메시지를 복원하며 앱은 구체적 설명을 일반 인증 문구로 덮어쓰지 않습니다.
