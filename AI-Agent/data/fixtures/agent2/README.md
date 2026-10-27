# 합성 AI2 회귀 예제

사람이 만든 이벤트 8개, episode 4개, 기존 MVP-01 graph와 개발용 manifest입니다. 실제 기기·사용자 기록·제품 gold가 아닙니다. 한 경로의 실패와 기존 대체 경로의 성공을 가정해 우선순위 후보를 생성하는 처리 흐름만 검사합니다.

[실행 명령](../../../src/ai_agent/graph_proposal/README.md)을 참고합니다. synthetic 시각은 100~1000이므로 예제 실행은 `--now 200`을 사용합니다. 독립 기기 holdout이 없어 최종 평가는 NEEDS_DATA가 정상입니다.
