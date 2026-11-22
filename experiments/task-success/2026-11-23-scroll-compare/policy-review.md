# `feat/settings-scroll-jev-retry` 검토

대상은 remote `origin/feat/settings-scroll-jev-retry`의 `4fca0099`이다. develop 비교 기준은 `74cf6635`. 아래의 코드/제어 실험과 실기기 본 비교는 별도 근거다.

## 방향

낮은 신뢰도 한 번만으로 사용자에게 바로 넘기는 대신 새 관찰을 한 번 받는 것은 **제한된 가설로는 합리적**이다. 모델 출력이 임계값 주변에서 흔들리는 경우 보류를 줄일 수 있다. 그러나 동일한 입력에 대해 수치가 높게 나올 때까지 다시 묻는 방식은 근거를 더 얻는 것과 다르다. 임계값 0.8을 유지해도 반복 선택 전체의 오안내율이 유지된다고 보장할 수 없다. 따라서 처리율, 잘못된 안내, 비용, 지연을 같이 재야 한다. 성공률 상승을 사전에 보장하지 않는다.

이 정책은 다음을 해결하지 못한다: 화면 밖 항목을 후보로 가져오지 못함, 스크롤 요청 후 실제 이동 없음, 탭 요청 후 실제 전환 없음, 도착 증거 판정기의 패키지 누락. 이런 경우 같은 Jev 질문을 다시 보내는 것은 주된 수정이 아니다.

## 원본 그대로 도입하기 어려운 이유

1. `primary.py`는 `abstained`와 `low_confidence`를 원인 구분 없이 `settling`으로 바꾼다. 모델이 대상 증거 부족/페이지 충돌이라고 답한 사실이 화면 애니메이션 대기처럼 전달된다. 기존 원인별 도움/스크롤 정책과 어긋난다.
2. `android_settings`로 적용 범위를 한정하지 않아 모든 primary Jev 그래프에 영향이 간다. 기본 정책과 실험 정책을 선택하는 flag가 없다.
3. 1회 제한에 전체 snapshot fingerprint를 쓴다. 이 fingerprint에는 unrelated OS applied settings가 포함된다. **동일 UI에서 밝기 값만 매 turn 달라져도 예산이 다시 생긴다.**
4. 동일 checkpoint capture/restore 조건의 제어 실험(실제 AI 호출 아님), 8 turn:

| 정책 | 밝기 고정 시 호출 | unrelated 밝기 drift 시 호출 |
|---|---:|---:|
| 기존 | 1 | 1 |
| 원본 실험 | 2 | 8 |
| 제한 수정안 | 2 | 2 |

`probe_retry_budget.py`와 `*-budget-probe.json`으로 재현한다. 원본 실험의 기존 backend primary recovery 테스트는 7 failed / 15 passed, AI-Agent는 2 failed / 551 passed였다. 의도적으로 정책을 바꾼 실패도 있지만, 기존 보류 정책을 유지했다고 볼 수 없다는 근거다.

## 제한 수정안

`CLICK_JEV_THRESHOLD_REOBSERVE=1`인 명시적 실험에서만 동작한다. 기본값 0은 기존 정책이다.

- native 후보가 1개이고 페이지/대상 일치, 대상 지지 확률이 원래 최소 신뢰도 이상인 낮은 신뢰도 판단만 대상이다.
- Settings의 `tap / state_reached / low_risk / no confirmation / not completes_task` 중간 이동만 허용한다. 슬라이더 조절/최종 설정 적용을 재질문 허용 대상으로 확대하지 않는다.
- abstained, 후보 부족, 충돌, 대상 미지지, 결과 불확실은 자동 재질문하지 않는다.
- node ID/레이아웃 및 unrelated applied value에 흔들리지 않는 기존 semantic selection fingerprint를 쓴다. 같은 근거 1회, 과제 전체 최대3회 ledger를 checkpoint에 저장한다. 화면 왕복과 checkpoint 복구로 예산이 환불되지 않는다.
- 실제 원인 `low_confidence`를 보존하고 필요한 변화를 fresh observation으로 표현한다. 새 응답이 기존 임계값을 통과하기 전에는 안내 대상을 만들지 않는다.
- default-off checkpoint는 빈 ledger를 생략해 기존 저장 모양을 유지한다. 실험 ledger가 든 checkpoint는 새 runtime이 필요하다.

본 비교의 guarded 소스는 `main-provenance.json`에 고정했다. 이후 develop용 telemetry에서 `fresh_observation`을 closed vocabulary에 추가하는 관측성 보완은 동작 정책을 바꾸지 않는다.

## 기존 브랜치 보고서 해석

브랜치의 약한 14과제 재측정 15/28과 이전5/28은 날짜/서버/빌드 조건이 다른 비대응 비교다. 이를 재질문 하나의 +35.7%p 효과라고 말하면 안 된다. display/Wi-Fi 6/8과10/12 역시 표본 수와 조건이 다르다. 이번에는 같은 기기/수정 APK와 대응 과제를 세 정책에 적용해 비교한다.

A와 B 사이에는 develop의 runtime phase 관측 코드 유무도 다르다. task selection의 의미 차이는 위 2개 retry 파일이며, server timing instrumentation 차이까지 포함한 두 source tree를 그대로 시험한다. 미세 지연 차이를 전부 재질문 때문이라고 단정하지 않는다.

## Android 및 평가 도구 보완

- 가려진 자동 탭: 실제 touchable task panel/버블의 중심점 가림만 검사한다. 투명한 전체 화면 guide는 장애물로 보지 않는다. 가리면 패널을 접고 100ms 후 generation/현재 guide/중지 상태를 재확인한다. native target 재검증도 그대로 유지한다. 여전히 가리면 도움 요청으로 끝내고 탭하지 않는다. 물리 gesture fallback 직전에도 가림을 확인한다.
- 이 수정은 좌표 가림 위험을 막는다. 과거 Wi-Fi 실패 모두의 원인이라고 확정하지 않는다. 본 비교에서 성공한 Wi-Fi의 실제 title gesture 시점에는 패널이 이미 아래로 접혀 있었다. 원래 실험의 Wi-Fi 실패는 accepted 판단 뒤 native 재검증 과정에서 다시 low confidence/abstained가 된 사례여서 단순 가림과도 구분해야 한다.
- 삼성 벨소리/알림 소리: 별도 시스템 앱 `com.samsung.android.secsoundpicker`가 열리는 것을 확인했다. 해당 두 과제의 공개 toolbar 제목만 독립 판정/증거 대상으로 추가했다. 개인 벨소리명/목록은 저장하지 않는다.
- `ACTION_SCROLL_BACKWARD`의 true를 맨 위 도착 신호로 사용하지 않는다. 요청 전 사전조건은 Settings 프로세스 재시작과 홈 제목 확인으로 교체했다.
