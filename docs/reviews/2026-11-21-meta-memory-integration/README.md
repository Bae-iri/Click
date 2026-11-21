# MetaController × 기억 통합 검사

[결과](report.md) · [실행 전 프로토콜](protocol.md) · [소스 해시](source-freeze.json)

## 로컬 충돌 재현

```sh
cd Frontend
CLICK_META_MEMORY_OUTPUT=/tmp/meta-memory-new-run bash ./gradlew :app:testDebugUnitTest \
  --tests com.click.service.overlay.MetaControllerClosedLoopExperimentTest.runMemoryInteractionMatrix
```

출력 폴더에 `episodes.jsonl`이 있으면 덮어쓰지 않는다. 운영 수정 전후 자료는
`before/`, `after/`로 보존했다. 일반 회귀 검사는 별도 환경 변수 없이 실행한다.

## 실기기

실제 Gemini 호출과 공개 설정 UI 전송을 승인한 환경에서만 실행한다.
접근성 서비스를 유지하는 debug 변형을 쓰고 기존 실험 앱과 다른 패키지로 설치한다.

```sh
cd Frontend
bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  -PCLICK_APPLICATION_ID=com.click.metamemoryexperiment.uitestdriver \
  -PCLICK_API_BASE_URL=http://127.0.0.1:8766 -PCLICK_CANDIDATE_DIAGNOSTICS=true -PKAKAO_NATIVE_APP_KEY=
```

저장소 루트에서 빈 로그 폴더를 지정해 서버를 실행한다. `Backend/.env`에
`GEMINI_API_KEY`가 있어야 한다. 이 실행은 모델 공급자 비용을 발생시킨다.

```sh
python Backend/scripts/serve_memory_experiment.py --allow-live --env-file Backend/.env \
  --catalogue docs/reviews/2026-11-20-memory-reuse/catalogue.json --log-dir /tmp/meta-memory-current
```

```sh
adb install -r -t Frontend/app/build/outputs/apk/debug/app-debug.apk
adb install -r -t Frontend/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb reverse tcp:8766 tcp:8766
adb shell am instrument -w -r \
  -e class com.click.service.accessibility.MemoryDeviceExperimentTest#probeMemorySetups \
  -e memorySetupProbe true -e tasks settings_display,settings_notifications \
  com.click.metamemoryexperiment.uitestdriver.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r \
  -e class com.click.service.accessibility.MemoryDeviceExperimentTest#runMemoryExperiment \
  -e memoryExperiment true -e metaMemoryIntegration true -e metaArm CURRENT -e arms A,B \
  -e tasks settings_display,settings_notifications -e budgetMs 25000 -e verifyWaitMs 1500 \
  -e runId unique-current-run \
  com.click.metamemoryexperiment.uitestdriver.test/androidx.test.runner.AndroidJUnitRunner
```

실행 뒤 `run-as`로 `files/memory-reuse/episodes.jsonl`과 `manifest.json`을 먼저 보존한다.
그 다음 새 서버 프로세스·빈 로그 폴더·다른 runId를 사용해 `-e metaArm META`로 반복한다.
이번에는 두 번째 서버가 8767을 사용해 `adb reverse tcp:8766 tcp:8767`로 연결했다.
기기 파일은 두 번째 실행에서 덮어쓰므로 첫 번째 자료를 먼저 보존해야 한다.

```sh
python Backend/scripts/summarize_meta_memory_integration.py docs/reviews/2026-11-21-meta-memory-integration
```

실제 학습이 두 번만으로 회수 기준을 충족한다고 가정하지 않는다. 회수·경로 분석 원자료와
`memory_proposals`를 확인한다. 실험 앱은 종료 시 기존 접근성 서비스 설정을 복원한다.
