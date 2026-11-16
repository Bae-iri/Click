# 설정 메뉴 이동과 복구의 공통 기능 계약

공통 파일: `AI-Agent/goal_catalog/settings_capabilities.json` (`settings-capabilities.v1`). 승인된 실제 graph에서 생성하며 Python 런타임, Android native 메뉴 정책, 화면 복구, 탐색 한도에 함께 사용한다.

## 계약의 범위

| 항목 | 의미 |
| --- | --- |
| `goals.*.navigation` | 확정 goal·graph 단계별 저위험 메뉴 이동 경로, 목표 이름, 패키지 |
| `goals.*.success` | 작업별 성공 종류·설정·방향·요청 상태. 페이지 도착과 값 변경, 전원 상태, 사용자 보고를 구분 |
| `goals.*.page_titles` | 별도 스위치가 함께 있는 행을 처리할 때 필요한 native 페이지 제목 |
| `navigation_states`, `state_aliases` | 목록 탐색이 가능한 상태와 이전 서버 상태 이름 호환 |
| `navigation_labels`, `label_aliases` | 승인된 메뉴 이름과 명시적인 동의어 |
| `private_identity_tokens`, `blocked_label_tokens` | 기기·계정 행과 위험 조작 제외 |
| `split_switch_rows` | 별도 Switch와 함께 있는 메뉴에서 제목을 구분하는 native 리소스 |
| `search_budgets` | 런처/설정 목록의 이동 수·경과 시간 상한 |

이 계약은 메뉴 이동 허가와 작업별 성공의 의미를 정의한다. 성공 기준이 자동 조작 권한을 추가하지는 않는다. 일반적인 Switch·Radio·삭제·인증·권한 승인·설정값 변경을 자동 탭으로 허가하는 계약이 아니다. 기존 슬라이더 승인·결과 검증과 스위치 직접 조작 범위는 유지한다.

## 생성·확장 절차

1. Backend의 승인 graph 또는 해당 graph의 원본 설정 정의를 수정한다. 현재 graph 버전 정책과 검토 절차를 지킨다.
2. `Backend`에서 `PYTHONPATH=.:../AI-Agent/src python3 scripts/export_settings_capabilities.py`를 실행한다.
3. `PYTHONPATH=.:../AI-Agent/src python3 scripts/export_settings_capabilities.py --check`로 생성물이 최신인지 확인한다.
4. `tests/test_settings_capabilities.py`와 Android 정책·오버레이 서비스 검사를 실행한다. CI도 생성 계약 최신성을 검사한다.
5. 앱을 빌드한다. Gradle이 동일 JSON에서 `SettingsCapabilitiesData` 코드를 생성하므로 JVM 검사와 휴대폰 앱이 같은 데이터를 사용한다. 생성 Java를 직접 수정하거나 커밋하지 않는다.

목표 graph에서 최종 값 조작·완료 전환·동의가 필요한 전환은 메뉴 이동 허가에서 제외한다. 개인정보 정책 밖의 패키지도 허가하지 않는다. 새 상태를 추가할 때 Android의 별도 enum/메뉴 목록을 수동으로 수정할 필요가 없다.

## Android 자동 이동

native 메뉴의 패키지·창·역할·실제 계층·위치·화면 신선도를 검증한 뒤, 확정된 goal과 응답 단계에 속한 메뉴인지 다시 확인한다. 다른 goal의 정상 메뉴라도 자동 조작하지 않는다.

삼성 ‘연결’처럼 메뉴와 Switch가 같은 행에 있으면 다음 조건을 모두 만족해야 한다.

- 현재 goal/단계의 독립 native 페이지 제목을 확인한다.
- 정확한 `android:id/title`과 승인된 메뉴 이름이 하나만 존재한다.
- 별도 native Switch가 하나 있고 제목과 영역이 겹치지 않는다.
- 개인정보 행·입력·위험한 조작·다른 독립 클릭 영역이 없다.

이 경우 Switch나 행 전체의 ACTION_CLICK 대신 **실제 제목 영역의 중앙**에만 제스처를 보낸다. 제목/스위치가 겹치거나 다른 화면·다른 goal이면 직접 진행으로 전환한다. 실행 직전에 원래 행 전체와 실제 native 제목을 다시 검증한다.

## 화면 상태와 복구

기존 `S2_DISPLAY_MENU`, `S2_CATEGORY`, `S3_FONT_MENU`와 확장 `S_CATEGORY`, `S_INTERMEDIATE`는 같은 생성 계약으로 목록 탐색 가능 여부를 판단한다. 이전 `S2_SOUND_MENU`는 명시적인 호환 별칭이다. 최종 값 조작 단계인 `S_TARGET`, 완료 상태는 메뉴 탐색 대상으로 허가하지 않는다.

Backend의 native 목록 미발견 복구도 같은 계약과 실제 graph 전환을 함께 확인한다. 관측 불가나 개인 입력을 단순 미발견 메뉴로 취급하지 않는다.

## 탐색 예산

- 런처: 이동 시도 최대 8회, 첫 탐색 이후 45초.
- 설정 목록: 이동 시도 최대 32회, 첫 탐색 이후 120초.
- 진행 없음·페이지 반복·끝 확인 시에는 위 최대 한도보다 일찍 종료한다.
- 상한은 활성 session/episode에 누적된다. 단계별 방문 이력은 별도로 유지한다.
- 창 ID·접근성 노드 경로·컨테이너 ID·다른 단계로 잠시 이동·자동 모드 변경·홈 복구가 같은 episode의 예산을 환불하지 않는다.
- 신규 작업/episode 또는 서비스의 명시적 새 세션 초기화는 별도 탐색 범위다. 이 변경은 서비스 프로세스가 종료된 뒤 예산을 영구 저장하는 기능을 추가하지 않는다.
- 횟수를 소진하거나 시간이 끝나면 직접 찾기/도움 안내로 전환한다. 설정 진입 순서는 보이면 하이라이트 → 없으면 페이지 탐색 → 실패하면 설정 열기를 유지한다.

## 실행 구성의 해시

`source_digest()`에 `AI-Agent/goal_catalog`의 모든 실행 JSON을 포함한다. `settings_execution.json`, `settings_capabilities.json`, 목표 목록, 화면 정책·어휘 변경도 소스 버전 해시를 바꾼다. 별도 runtime `code_hash`에도 공유 JSON을 포함한다.

`/version`과 runtime 응답 metadata의 `capability_contract`는 스키마와 SHA-256만 제공한다. Android는 자신이 포함한 JSON의 동일 해시를 계산한다. 새 서버가 계약을 제공했는데 해시가 다르면 자동 탭을 하지 않고 직접 진행 안내로 전환한다. 이전 서버가 이 선택 필드를 생략하면, 앱에 포함된 승인 계약으로 기존 호환 동작을 유지한다.

앱은 새로 빌드/설치해야 하고 Backend·AI 런타임 및 `/version` 변경은 서버 재배포가 필요하다. 계약 파일은 앱과 서버에 함께 포함한다. 이 작업은 기존 DB의 같은 버전 graph를 임의로 덮어쓰거나 활성 버전을 변경하지 않는다.


## 동일 화면과 성공의 구분

상세 규칙과 상태 전환은 `docs/task-observation-and-lifecycle.md`를 따른다. `goals.*.success`는 승인 graph의 required_slots와 settings_execution의 mode에서 생성한다. Python의 `task_success.technical_success`와 Android의 `TaskSuccess.verified`가 동일한 계약을 읽으며, `AI-Agent/tests/fixtures/task_success_cases.json`의 공통 양성·음성 사례를 두 환경에서 실행한다. 새 작업에 성공 조건을 구현하지 않았다면 `user_report`로 두고 기술적 성공을 선언하지 않는다.
