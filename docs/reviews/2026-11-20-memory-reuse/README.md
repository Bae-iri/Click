# 기억 재사용 실기기 실험 자료

[결과·판단](report.md), 사전 고정 프로토콜 [1차](protocol.md)·[2차](protocol-v2.md), 집계 [1차](results/summary.md)·[2차](results-v2/summary.md).

2차는 같은 하네스로 `-e runId mem-v2-20261120`과 새 로그 폴더를 써서 실행했다.

## 재현 절차

접근성 서비스가 필요하므로 `uiTestDriver` 변형이 아닌 격리된 debug 패키지를 쓴다.

```bash
cd Frontend
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  -PCLICK_APPLICATION_ID=com.click.memoryexperiment.uitestdriver \
  -PCLICK_API_BASE_URL=http://127.0.0.1:8766 -PKAKAO_NATIVE_APP_KEY=
```

저장소 루트에서 실험 서버를 띄운다. 실제 모델 호출이 발생하므로 `--allow-live`가 필요하다. 로그 폴더는 비어 있어야 한다. 이 서버는 실행마다 새로 띄워야 한다.

```bash
python Backend/scripts/serve_memory_experiment.py --allow-live --env-file Backend/.env \
  --catalogue docs/reviews/2026-11-20-memory-reuse/catalogue.json \
  --log-dir /tmp/memory-facade-logs
```

```bash
adb install -r -t Frontend/app/build/outputs/apk/debug/app-debug.apk
adb install -r -t Frontend/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb reverse tcp:8766 tcp:8766
adb shell am instrument -w -r \
  -e class com.click.service.accessibility.MemoryDeviceExperimentTest#probeMemorySetups \
  -e memorySetupProbe true \
  com.click.memoryexperiment.uitestdriver.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r \
  -e class com.click.service.accessibility.MemoryDeviceExperimentTest#runMemoryExperiment \
  -e memoryExperiment true -e arms A,B,C -e budgetMs 25000 -e runId mem-v1-20261120 \
  com.click.memoryexperiment.uitestdriver.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.click.memoryexperiment.uitestdriver cat files/memory-reuse/episodes.jsonl > /tmp/episodes.jsonl
python Backend/scripts/summarize_memory_experiment.py --episodes /tmp/episodes.jsonl \
  --logs /tmp/memory-facade-logs --output-dir /tmp/memory-results
```

시계 앱이 타이머 탭으로 열리면 준비 단계가 실패한다. 시작 전에 알람 탭으로 돌려 둔다.
