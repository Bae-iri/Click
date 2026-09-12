# 공통 Telemetry

Agent(1)과 Backend가 진단을 생산·필터링하고 Agent(2)가 읽을 관측 계약을 정의하는 경계다. 이 패키지는 Goal 실행, 결과 라벨링, graph 수정·배포를 수행하지 않는다.

- `trace_logger.py`: 기존 진단 allowlist, 메모리 개수·TTL 제한을 유지하는 `TraceLogger`와 `diagnostic_payload`.
- `schemas.py`: 기존 요약 입력 계약인 `RuntimeTrace`와 온라인 verification 상태 타입.

`TraceLogger.traces`의 실제 진단 dict와 `RuntimeTrace` dataclass는 같은 schema가 아니다. 진단 dict는 임의 문자열·식별자·화면 증거를 제거하므로 `RuntimeTrace(**diagnostic)`로 변환하거나 진단 로그만으로 episode를 재구성하면 안 된다. 전체 학습 event의 영속화·adapter는 별도 구현 대상이다.

`RuntimeTrace.result`는 runtime이 관측한 verification 상태이며 AI-Agent(2)의 확정 success/fail 라벨이 아니다. 원천 관측과 라벨을 별도로 보관한다.

이 패키지는 두 에이전트의 구현을 import하지 않는다. Agent(1)에서 `TraceLogger`를 사용해도 Agent(2)가 로드되지 않아야 한다.
