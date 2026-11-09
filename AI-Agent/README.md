# Click AI Agent

| 영역 | 책임 |
| --- | --- |
| [Agent 1 runtime](src/ai_agent/runtime/README.md) | Backend가 전달한 graph로 화면 분석·안내·행동 검증·세션 내 복구 |
| [Agent 2](src/ai_agent/graph_proposal/README.md) | Jev 판단·점수와 실행 성공률 분석, 독립 대상 검수, 수정 전후 비교; 기존 경로 후보는 선택 기능 |
| telemetry | 개인정보를 제한한 실행 증거·진단 계약 |
| task_graph | 공통 graph 계약·registry |
| user_modeling | Backend가 사용하는 개인화·안내 난이도 계산 |
| observation / matching / guide / safety / schemas.py | Backend의 기존 guidance adapter와 공통 graph가 사용하는 호환 코드 |

FE/Backend는 목표를 확인하고 Backend가 승인 graph를 선택·version/hash 고정·복원합니다. AI1은 해당 graph를 실행합니다. Agent 2는 사용자 응답을 기다리게 하지 않으며 검토 없이 진행 중 graph를 바꾸지 않습니다.

AI1 실행 코드는 `src/ai_agent/runtime/`에 모았습니다. OCR 보조 기능은 `runtime/vision.py`의 `VisionFallback`입니다. 호출되지 않던 초기 골격과 번호가 붙은 복사본은 제거했습니다. `runtime/goal_typing.py`와 기본 task registry는 기존 raw goal 요청·회귀 테스트에서 계속 사용합니다.

## 현재 상태

AI1은 승인된 Android 설정 110개(설정 변경 검증 50개·화면 이동 21개·직접 진행 안내 39개)의 graph를 실행합니다. 직접 진행 안내는 기술 완료와 사용자 보고를 구분하며, 전체 기종·목표의 실제 완료 검증은 남아 있습니다. [Goal 카탈로그](../docs/goal-type-keyword-catalog.md), [Vision](../docs/ai-agent-vision-fallback.md), [실행 정책](../docs/ai-agent-1-runtime-policy.md)을 참고합니다.

AI2는 DB 수집 연결부터 재구성·분석·기존 경로 우선순위 후보·replay 평가·승인/롤백까지 구현했습니다. CPU 빈도 baseline도 제공합니다. 실제 제품 gold·독립 holdout·새 UI 경로의 증거는 아직 없으며 자동 신규 작업 생성이나 범용 모델 학습 완료를 뜻하지 않습니다.

현재 중심 기능은 [Jev 판단과 실행 결과 평가](../docs/ai2-jev-assessment.md)입니다. 점수·보류 이유와 실제 결과를 연결하며, 선택 정답률은 독립 검수로 계산합니다. 개인정보와 실행·분석 기록은 [저장 전 암호화](../Backend/docs/data-encryption.md)를 적용합니다.

```powershell
cd AI-Agent
python -m pytest -q
```

[AI2 명령·합성 예제](src/ai_agent/graph_proposal/README.md), [데이터 구분](data/README.md), [전체 구조](../docs/ai-agent-architecture.md)를 참고합니다.
