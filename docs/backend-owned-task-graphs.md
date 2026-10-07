# 백엔드가 작성·관리하는 Task Graph

## 현재 구현

`Backend/app/task_graphs/`가 그래프 정의의 원본이다. 기존 글자 크기 키우기·줄이기 그래프는 `artifacts/MVP-01.json`, `artifacts/MVP-02.json`으로 옮겼다. 기존 AI-Agent 정의와 내용·해시가 동일하며, 백엔드는 더 이상 AI-Agent의 등록 목록에서 실행 그래프를 찾지 않는다. 독립 실행용 AI-Agent의 기존 정의는 호환성을 위해 남아 있다.

Android 설정 89개 Goal 모두에 그래프가 있다. 기존 두 개는 승인 상태, 나머지 87개는 `drafts.json`에 화면 분류 후보·안내 대상 후보·완료 조건을 정의한 초안이다. 초안의 분류 경로는 제안이며 특정 제조사에서 확인된 경로가 아니다. 공통 작성 코드 `drafts.py`는 이를 설정 홈 → 분류 → 설정 항목 → 결과 검증 → 완료 구조로 구성한다. 새 초안은 승인·활성화하지 않아 기존 요청 차단 동작을 유지한다.

백엔드 시작 시 기존 DB 테이블 `approved_task_graphs`에 두 실행 그래프와 초안을 등록한다. 테이블 이름과 달리 `approved=false, active=false`인 후보도 저장한다. 원본 JSON과 해시·버전·상태가 함께 보존된다. 추가 DB 마이그레이션은 없다.

## 실행 연결

백엔드는 승인·활성 그래프를 Goal로 선택하고 DB JSON을 검증·역직렬화한 뒤 AI1에 전달한다. AI1 코드에 해당 task ID가 없어도 실행할 수 있다. 그래프 해시·schema·DB 메타데이터 일치를 검사하며, 잘못된 그래프를 실행하지 않는다. checkpoint도 전달받은 그래프를 사용한다.

진행 중인 작업은 선택한 task ID·버전·해시에 고정한다. 새 릴리스가 생겨도 관측과 정확한 재시도는 이전 릴리스를 사용한다. 그래프 참조와 turn 결과는 같은 트랜잭션으로 저장한다. 이전 글자 크기 checkpoint의 bundled hash도 호환 처리한다.

`TaskGraphRepository.register(body, version=..., approved=False)`로 새 후보를 등록한다. 같은 task ID·버전의 본문·승인 여부·우선순위는 불변이며, 바꾸려면 새 버전으로 등록한다. `approved=True` 등록은 지원되는 완료 조건과 확인 메시지·유효한 화면/전이를 갖춰야 한다. 이 메서드는 서버 내부 등록 API이며 외부 사용자가 호출할 수 있는 endpoint는 추가하지 않았다.

## 새 초안을 실행 가능하게 만들기

1. 실제 Android 화면 관측으로 제조사·OS별 분류와 버튼·스위치 selector를 확정한다.
2. 실제 대상 앱·값·삭제 범위처럼 현재 intent-only 계약에 없는 정보가 필요하면 Goal 계약을 확장한다. 민감한 비밀번호·인증 입력은 수집하지 않는다.
3. 필요한 완료 조건을 AI1 verifier에 구현하고 재현 fixture로 검증한다. 스위치는 원하는 켜짐/꺼짐, 삭제는 실제 대상의 삭제 결과를 확인해야 한다.
4. 새 버전의 그래프를 승인 등록하고 catalog 실행 상태를 함께 갱신한다.

현재 AI1이 지원하는 조건은 `state_reached`, `target_value_increased`, `target_value_decreased`다. 초안의 `target_checked_on/off`, `verified_*` 조건은 아직 구현하지 않았으므로 승인 검증을 통과하지 못한다. 초안만으로 전체 기능이 실행 가능해진 것은 아니다. 일부 수치 변경 초안은 기존 predicate를 사용하지만 실기기 selector 검증 전까지 비활성 상태다.

Android는 설정 화면 진입용 액션을 제공하지만 액션을 처리하는 Activity가 없는 경우도 있으므로 확인이 필요하다. 문서의 액션을 제조사 공통 화면 경로의 증거로 취급하지 않는다. [Android Settings 공식 문서](https://developer.android.com/reference/android/provider/Settings).
