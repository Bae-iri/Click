# Click AI Agent

| 영역 | 책임 |
| --- | --- |
| [Agent 1 runtime](src/ai_agent/runtime/README.md) | Backend가 전달한 graph로 화면 분석·안내·행동 검증·세션 내 복구 |
| [Agent 2](src/ai_agent/graph_proposal/README.md) | 오프라인 기록 재구성·분석·데이터셋·그래프 후보·평가·승인 |
| telemetry | 개인정보를 제한한 실행 증거·진단 계약 |
| task_graph | 공통 graph 계약·registry |

FE/Backend는 목표를 확인하고 Backend가 승인 graph를 선택·version/hash 고정·복원합니다. AI1은 해당 graph를 실행합니다. Agent 2는 사용자 응답을 기다리게 하지 않으며 검토 없이 진행 중 graph를 바꾸지 않습니다.

## 현재 상태

AI1의 글자 확대/축소 안내·행동 검증·복구와 Backend 연결이 구현되어 있습니다. 대상 오선택 개선·실기기 완료 판단·제조사별 검증은 남아 있습니다. [Vision](../docs/ai-agent-vision-fallback.md), [실행 정책](../docs/ai-agent-1-runtime-policy.md)을 참고합니다.

AI2는 DB 수집 연결부터 재구성·분석·기존 경로 우선순위 후보·replay 평가·승인/롤백까지 구현했습니다. CPU 빈도 baseline도 제공합니다. 실제 제품 gold·독립 holdout·새 UI 경로의 증거는 아직 없으며 자동 신규 작업 생성이나 범용 모델 학습 완료를 뜻하지 않습니다.

```powershell
cd AI-Agent
python -m pytest -q
```

[AI2 명령·합성 예제](src/ai_agent/graph_proposal/README.md), [데이터 구분](data/README.md), [전체 구조](../docs/ai-agent-architecture.md)를 참고합니다.
