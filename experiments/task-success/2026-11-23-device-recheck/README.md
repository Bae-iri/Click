# 11월 23일 설정 경로 실기기 재측정과 원인 분석

Galaxy Note20(SM-N981N, Android 13) 1대, Windows에서 띄운 로컬 격리 서버(develop b7caf491 + 이 브랜치의 수정), 실제 Jev·OpenRouter, 기존 `LiveOverlayAuditTest` 하네스(75초 제한, `-PCLICK_CANDIDATE_DIAGNOSTICS=true` 빌드)로 측정했다. 성공 기준은 기존 하네스의 `제품 완료 확인 + 독립 기기 도착`이다. [2026-11-23 사용자 판단 설계](../2026-11-23/protocol.md)의 지표가 아니며, 운영 서버 측정도 아니다. **수정 효과와 원인 확인용 자료이고 발표용 성공률로 쓰지 않는다.**

## 결과

### 등록 경로 디스플레이·Wi-Fi (`--cohort registered`, 4건 단위 반복)

| 서버 | 묶음 | 디스플레이 | Wi-Fi | 합계 |
|---|---|---:|---:|---:|
| develop 그대로 | run1, run2 | 4/4 | 2/4 | 6/8 |
| 희소 화면 재관찰만 (중간안, 폐기) | run5, run6 | 4/4 | 2/4 | 6/8 |
| Jev 보류 시 같은 화면 1회 재관찰 (이 브랜치) | run7–run9 | 6/6 | 4/6 | **10/12** |

11월 22일 재측정(Mac)의 HTTP 504 세 건은 재현되지 않았다. 같은 조건에서 서버의 `start_task` 런타임 처리는 15–406ms였고, 턴 전체는 Windows `spawn` 오버헤드를 포함해 1.3–1.7초였다. 504는 Mac의 forkserver 런타임 프로세스 쪽 환경 문제로 추정하며 확인하지 않았다.

### 약한 과제 14종 × 2회 (이 브랜치 서버, repeat 1은 설정 목록을 스크롤한 상태에서 시작)

| 묶음 | 11월 22일 52회 실험 | 이번 |
|---|---:|---:|
| Wi-Fi·블루투스 | 2/4 | 4/4 |
| 시인성 향상·청각 보조·TalkBack | 0/6 | 6/6 |
| 글자 크기·화면 크기·입력 및 동작 | 3/6 | 3/6 |
| 미디어 음량 올리기 | 0/2 | 2/2 |
| 밝기 올리기·내리기 | 0/4 | 0/4 |
| 방해 금지·벨소리·알림 소리 | 0/6 | 0/6 |
| **합계** | **5/28** | **15/28** |

과제당 2회, 기기 1대다. 개선분 일부는 아래 정책 변경의 효과이므로 그 변경이 합의되기 전에는 확정치로 쓰지 않는다.

## 원인

| 순위 | 원인 | 실패 | 근거 |
|---|---|---:|---|
| 1 | **삼성 설정 첫 화면 목록이 스크롤되지 않음** | 6 (밝기 4, 방해 금지 2) | 큰 "설정" 제목 때문에 첫 화면에 '연결'·'기기 간 연결'만 보인다. 정규화 노드 2개, 후보 0개(`NO_CANDIDATES`). `ClickScroll`은 `forward=true dispatched=true`를 반복했지만 수 초 간격 스크린샷이 동일했다. 접근성 스크롤 액션이 성공으로 반환되지만 목록이 움직이지 않는다. |
| 2 | '소리 및 진동'에서 같은 항목 탭이 반복되고 화면이 넘어가지 않음 | 4 (벨소리 2, 알림 소리 2) | 첫 선택은 정답, 이후 `S_CATEGORY`에서 같은 노드 탭이 5–7회 승인되고 75초 초과. 탭 좌표·항목 위치는 미확인. |
| 3 | Jev 확신도가 최소 0.8 경계에서 흔들림 | 3 (화면 크기 2, 글자 크기 1) + 등록 경로 Wi-Fi 실패 | '연결' 화면의 같은 'Wi-Fi' 단일 후보가 시행마다 target support 0.86 / 0.78 / 0.77. 기준 미달이면 `MODEL_ABSTAINED → ask_user`로 즉시 도움 요청이 된다. |

## 이 브랜치의 코드 변경 (검토 필요)

`AI-Agent/src/ai_agent/runtime/primary.py`, `engine.py`: Jev가 `abstained`/`low_confidence`로 보류하면 **같은 화면에서 한 번** `settling`(재관찰)로 처리해 새 판단을 받는다. 같은 화면 지문에서 두 번째 보류가 나오면 기존처럼 사용자에게 묻는다. 0.8 기준은 바꾸지 않았다.

이 변경은 "보류 후에는 화면이 바뀌어야 재시도한다"는 기존 정책과 충돌한다. 관련 테스트 576개 중 **20개가 실패**한다(예: `test_provider_holds_require_a_change_before_retry`, `test_low_confidence_after_revalidation_stays_bounded_across_checkpoint_restore`). 같은 화면 재질문은 사실상 기준을 완화할 수 있으므로 Jev 담당자와 합의한 뒤 테스트를 고치거나, 대신 목표 설명 보강으로 확신도를 안정시키는 방향을 택한다. develop에 그대로 머지하지 않는다.

## 다음 수정 후보

1. **설정 목록 스크롤 폴백(Android):** 접근성 스크롤 뒤 스냅샷이 그대로면 `NativeScrollExecutor.revealSettingsTarget`과 같은 스와이프 제스처로 다시 스크롤한다. 원인 1의 6건과 첫 화면 아래 메뉴 전반에 영향.
2. 소리 화면 반복 탭: 탭 시점 스크린샷과 노드 bounds를 확인한다.
3. Jev 경계값: 위 정책 결정.

## 파일

- `trials/runN-*.json`: 등록 경로 시행별 기기 결과. run1–2 원본, run5–6 중간안, run7–9 이 브랜치.
- `trials/weak-*.json`: 약한 과제 시행별 기기 결과(과제당 repeat 0·1).
- `scripts/audit_server.py`: `Backend/scripts/serve_device_audit.py`의 Windows 실행판(임시 경로·턴 시간 계측). `CLICK_WT`, `CLICK_TIMING_LOG`, `CLICK_ENV_FILE` 환경변수 필요.
- `scripts/run_weak.py`: 지정 과제를 `auditRepeats=2`로 실행.

원본 logcat과 스크린샷은 계정 정보가 들어 있어 올리지 않았다.
