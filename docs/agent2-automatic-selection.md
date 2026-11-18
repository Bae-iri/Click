# Agent2 성공률 기반 자동 선택

후보별 개발자 승인을 자동 선택 정책 `ai2-success-selection.v1`으로 대체한다.
자동 선택은 현재 지원하는 행동과 실제 화면에서 확인된 후보 사이에서만 이루어진다.
새 앱, selector, 결제 행동이나 완료 조건을 성공 통계만으로 생성하지 않는다.

## 공개 상품 탐색

탐색 응답 이후 기존 Agent2 작업자가 연결된 사용자 행동을 분석하고,
암호화된 receipt에 `browse-outcomes.v1` 통계를 저장한다. 다음 요청은
`BrowseRouteHints.choose()`에서 성공 기록을 비교한다. 별도 승인이나 게시 명령은 없다.
기본 작업자 설정 `CLICK_AI2_BROWSE_GRAPH_ENABLED=1`과 학습 참여 동의가 필요하다.

- 후보 키는 목표·현재 페이지·행동·resource다. 현재 서버 code hash와 정책이 같은 기록만 사용한다.
- 같은 attempt hash의 반복 관측은 한 번만 센다. 뒤늦게 확인된 결과는 미확인을 대체한다.
- 검색창/검색 결과 이동, 선택 상품의 검증된 상세 도착, 목록 스크롤의 관측 변화가 단계 성공이다.
  단계 성공률은 전체 요청 완료율과 다르다.
- 확인된 잘못된 목적지는 실패, 탭 이후 결과 미확인은 unknown이다.
  `successes / (successes + failures + unknown)`으로 계산하여 성공 기록만 분모로 삼지 않는다.
- 최소 3회 시도와 성공률 70%를 충족해야 한다. 적은 표본의 과대평가를 줄이기 위해
  성공률의 Wilson 95% 하한을 비교하고, 같으면 성공률·시도 수로 비교한다.
  여기까지 동점이면 선택을 보류한다. 이 기준은 기본 정책이며 제품 품질 보증 수치가 아니다.
- 승자의 action/resource가 현재 화면의 노드 하나에만 대응해야 한다.
  상품은 현재 검색어가 상품명에 포함되어야 하며, 같은 resource를 가진 카드가 여러 개면 Jev로 넘긴다.
- 데이터 부족, 실패 누적, 동점, 만료, 철회, DB 오류 시 기존 Jev 선택을 사용한다.
- 자동 선택은 `reason=ai2_selected`, `selection_policy`, `selection_metrics`로 기록한다.
  모델 호출이 없어 `called=false`다. 현재 화면 검증·민감 화면 차단·사용자 직접 조작·완료 검증은 유지한다.

쿠팡 상품 검색·상세 탐색에 적용된다. 기존 모든 commerce 목표나 낯선 앱을 자동 지원한다는 뜻은 아니다.
앱 버전·기종별 분리는 아직 추가 계약이 필요하다. 현재 구현은 서버 code hash와 정책으로 범위를 구분한다.

## 기존 작업 그래프의 경로 우선순위

기존 `propose-routes`가 저장한 후보들 중 Agent2가 고르고 게시하는 명령을 제공한다.
운영 작업 스케줄러에서 다음 두 단계를 실행할 수 있으며 후보 ID·검토자·버전을 사람이 지정하지 않는다.

```powershell
cd C:\click_server\Click\Backend
python -X utf8 scripts/agent2.py propose-routes --manifest review-manifest.json --cases device-holdout.json
python -X utf8 scripts/agent2.py auto-select-routes --run-id <앞에서 출력된 run_id> --manifest review-manifest.json --cases device-holdout.json
```

`DATABASE_URL`과 기존 암호화 키를 실행 환경에 제공해야 한다. 평가 파일은 실제 독립 관측 증거여야 한다.
후보마다 새로 개발자 승인을 요구하지 않지만, 기존 독립 holdout의 provenance·누출 방지 검사는 유지한다.
학습 대상에서 baseline과 대체 경로가 각각 최소 3회 관측되고 성공률이 개선되어야 한다.
그런 후보들을 실제 replay로 재평가한 뒤 PASS 후보 중 task당 하나를 같은 성공률 정책으로 고른다.
같은 task의 여러 변경을 근거 없이 합치지 않는다. 대체 경로가 없는 그래프에서는 후보가 생기지 않는다.

게시 직전에도 receipt 만료·철회, 저장된 후보 내용, base hash, 평가와 현재 실행 설정을 검사한다.
버전은 후보·base·평가·정책으로 결정하며 `GraphReleaseAudit`에 자동 정책 ID와 선택 통계를 남긴다.
기존 세션은 고정한 version/hash를 유지하고 새 세션부터 변경을 사용한다. 기존 rollback 명령도 유지한다.
데이터가 부족하거나 평가에 실패하면 `NEEDS_DATA`이며 현재 활성 그래프를 변경하지 않는다.

기존 수동 `publish` 명령은 호환성을 위해 남아 있지만 자동 선택에는 필요하지 않다.
이 명령은 서버 스케줄러에 자동 등록되지는 않는다. 공개 상품 탐색 선택은 기존 작업자와 요청 경로에서 자동 실행된다.
