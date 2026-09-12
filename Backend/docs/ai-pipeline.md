# AI Pipeline

**Week 4 implemented:** graph-derived progress, bounded runtime execution, catalog-only embedding egress, provider validation/fallback, input redaction and diagnostic allowlists. See [week4.md](week4.md); earlier future-work statements below are historical.

## 현재 코드에 맞춘 흐름

`RuntimeAgentAdapter`가 HTTP DTO를 `ai_agent.runtime` 타입으로 바꾼다. runtime은 목표 typing → 화면 정규화·분류 → UI 매칭 → 이전 행동 검증 → 재계획 → guide 생성 과정을 수행한다. 이후 서버가 동의된 UI 프로필로 안내 문구를 조정한다.

현재는 task graph와 규칙 중심이며 모든 판단을 LLM 한 번에 맡기는 구조가 아니다. `GEMINI_API_KEY`가 없으면 local keyword scorer를 사용한다. 외부 유사도 호출은 코드상 3초, Vision은 8초 timeout이 있으나 전체 turn의 시간 예산은 아직 없다.

## 단계별 계약과 인수 기준

| 단계 | 입력 → 출력 | 검증·추가 작업 |
|---|---|---|
| Goal typing | 사용자 목표 → goal type·slots·신뢰도 | 앱별 task catalog, 모호한 요청 재질문, 미지원 목표 차단 |
| Observation | tree / 동의된 vision → normalized nodes | 입력 크기·민감필드 제거·유효 좌표·화면 시각 검사 |
| Classification | nodes·graph → 현재 state | 앱 package와 graph 일치, 판별 불가 시 추측 금지 |
| Match | 현재 state·nodes → 대상·다음 transition | 존재·활성·화면 범위 확인, 실패 노드 반복 제한 |
| Verify / Replan | 이전 기준·새 화면·이벤트 → 진행 결과 | 이벤트 단독으로 성공 처리 금지, 시도 상한·복구 |
| Safety / Guide | 후보 전이 → guide·confirmation·blocked | 서버 검증으로 허용 타입·승인 결합 보장 |

## 다음 구현 단위

1. 기존 글자 크기 전용 step/total_steps 매핑을 graph 기반으로 일반화한다.
2. 확정한 앱별 task graph와 화면 fixture를 등록한다.
3. 외부 호출 전 개인정보 경계와 전체 요청 timeout을 구현한다.
4. 모델 오류·형식 오류·목표 불확실성의 응답 규약을 고정한다.
5. runtime 상태 저장소를 영속 세션과 연결한다.

학습 관련 파일이 존재해도 dataset_builder·graph_learner 등은 골격인 부분이 있다. 학습 시스템 완성으로 계산하지 않는다. 우선 완성 범위는 합의한 task의 안정적인 추론·안내이며 실제 학습 도입은 별도 범위 결정이다.
