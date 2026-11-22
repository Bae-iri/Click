# 재배포 후 검사 인계 확인

2026-11-22 21:39 KST 확인. 운영 `/version`은 `bcd7ac97d7750df80a7b8d1dac54216ca6b7426a`다. 로컬도 기존 변경을 보존한 `pull --ff-only --autostash`로 이 버전에 맞췄다. Render 대시보드 조작이나 추가 배포는 하지 않았다.

## 이어받은 시행

- `nograph-before.json`: 앱 10종 + 설정 이동 20종, 각각 2회인 60회. 설정도 그래프 없이 범용 앱 안내로 수행한 자료다. 기존 그래프 기반 52회와 별도 조건이다.
- `nograph-after-part1.json`: 재배포 후 첫 13회 완료, 시행 13(접근성)은 미완료였다.
- `nograph-after-resumed-part1.json`: 시행 13을 재개해 완료했다. 명시적 도움 요청 상태로 목표 미도달, 실패다. 다음 시행 14는 중단됐으므로 집계하지 않는다.
- `nograph-after-resumed-part2.json`: 시행 14 재시작 직후 다른 instrumentation이 시작되면서 종료됐다. 완료 시행이 없다.
- 다음 재개 위치는 `auditStartTrial=14`, `auditSet=all`, `auditRepeats=2`다. 실기기 단독 실행을 먼저 확보해야 한다.

이전 자료의 `Process crashed` 메시지만으로 제품 크래시라고 판단하면 안 된다. Android `ApplicationExitInfo`에는 21:36:42의 종료가 `USER REQUESTED / FORCE STOP`, 설명이 `due to start instr`로 남아 있다. 동시에 다른 `ActiveInstrumentation` 프로세스가 확인됐다. 원자료는 `resumed-android-exit-info.log`다.

## 현재까지 확인된 결과

재배포 후 완료된 시행은 14회로, 전체 60회 결과가 아니다. 독립 기기 판정과 제품 완료 판정을 모두 충족한 시행은 3회, 거짓 완료는 0회다. 부분 표본을 발표용 최종 성공률로 사용하지 않는다. 첫 동작 제안 시간은 화면에 안내가 그려진 시간이 아니므로 슬라이드의 ‘첫 유효 안내’ 속도에 대입하지 않는다.

`summarize_app_tasks.py`는 중단 행을 버리고 `(repeat, task)`로 중복을 제거한다. 완료된 실패도 분모에 포함한다. `resumed-summary-progress.json`은 부분 집계이며 전체 평가 결과가 아니다.

## 검사 도구 변경

재개 번호, 시행 완료 여부, 경과 시간을 기록한다. 제품이 `needs_input` 또는 `protected`를 유지하고 화면 변화 없이 3초가 지나면 `EXPLICIT_HELP_REQUIRED` 실패로 종료한다. 기존 75초 상한은 유지한다. 이 변경은 테스트 도구에만 있으며 제품 코드와 성공 판정은 바꾸지 않았다. 이전 시행과 실패 종료 대기 시간이 달라졌으므로 실패까지의 시간은 직접 비교하지 않는다.

## 회귀 검사

- 그래프 릴리스, 삼성 설정 선택, 결과 계약 및 완료 판정: 66개 통과 (`resumed-backend-tests.log`).
- 실제 감사 사례 재현, 완료 triage, Jev 완료 선택: 144개 통과 (`resumed-completion-tests.log`).
- Android 검사 APK 빌드 성공 (`resumed-runner-build.log`).

합계 210개는 코드 회귀 검사 수이며 실기기 성공률의 분모에 더하지 않는다. 별도 검사가 현재 실기기를 사용하고 있어 원래 사용자 앱, 접근성 서비스, 화면 유지 설정을 추가로 변경하지 않고 단독 실행 확인을 기다린다.
