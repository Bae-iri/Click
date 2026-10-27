# Agent 데이터

실제 사용자 기록·합성 fixture·자동 라벨·독립 정답·graph 후보를 구분합니다.

| 위치 | 내용 |
| --- | --- |
| fixtures/mvp01_episodes.jsonl | 합성 20건: 기존 MVP-01 weak labeler 회귀 입력 |
| fixtures/agent2/ | 합성 이벤트 8개·graph·개발용 manifest: AI2 전체 처리 예제 |
| evaluation/runtime_recovery/ | 합성 화면/행동 replay와 독립 expected를 사용하는 개발 회귀 |
| evaluation/manifest-template.json | 광범위 품질 평가 설계용 빈 template; AI2 실행 manifest와 별도 |
| local/ | 명시적 fixture 실행·baseline 학습의 로컬 산출물. Git 제외 |
| graph_proposal/ | 기존 weak labeler 등의 재생성 가능한 로컬 출력. Git 제외 |

실제 runtime 이벤트와 동의된 AI2 분석은 Backend DB의 runtime_events/runtime_event_consumptions에 저장합니다. 예전의 빈 폴더 placeholder와 생성된 label 복사본은 제거했습니다. 실제 기록을 이 저장소에 자동 추가하지 않습니다.

AI2 실행 manifest는 ai2-dataset.v1이며 source/group/split/review/evidence를 검사합니다. [실행 예제와 계약](../src/ai_agent/graph_proposal/README.md)을 참고합니다. weak label의 final 표시는 제품 gold라는 뜻이 아닙니다.

공개 자료는 출처·revision·이용 조건·변환/제외 이력을 별도로 관리하며 제품 정답으로 자동 승격하지 않습니다. 실제 기기 근거와 독립 검수·holdout이 필요합니다. 별도 파일 반출물은 DB cascade로 삭제되지 않으므로 동의·보존·폐기를 관리해야 합니다.
