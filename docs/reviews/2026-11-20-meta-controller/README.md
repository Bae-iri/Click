# MetaController 실험 자료

[최종 결과·연구 질문·go/no-go](report.md), [조건별 원자료 집계](results/pilot-summary.md), [그래프](results/pilot-results.png)를 제공한다. 연구 범위와 판정 기준은 [pilot-protocol.md](pilot-protocol.md)에 있다. 원래 252회 제품 실험 계획은 `protocol/protocol.json`, 실제 기술 파일럿은 기기에서 가져온 `native-manifest.json`을 기준으로 구분한다. 개발·중단 자료는 `development/`에 보존하며 최종 성능에 합치지 않는다.

## 재현 절차

격리된 debug 패키지를 사용한다. `uiTestDriver` 변형은 접근성 서비스를 제거하므로 이 실험에는 사용할 수 없다. 기존 사용자 앱과 로그인은 덮어쓰지 않는다.

```bash
cd Frontend
bash ./gradlew \
  -PCLICK_APPLICATION_ID=com.click.metaexperiment.uitestdriver \
  -PCLICK_API_BASE_URL=http://127.0.0.1:8766 \
  -PKAKAO_NATIVE_APP_KEY= \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

저장소 루트에서 별도의 터미널로 로컬 facade를 실행한다. 실제 모델 호출을 허용하므로 `--allow-live`가 필요하다. 로그에는 API 키·원문 화면·요청·모델 응답을 기록하지 않는다. 기존 AppTaskService와 요청 스키마를 사용하며 경험 재사용이나 제품 API를 노출하지 않는다.

```bash
python3 Backend/scripts/serve_meta_controller_experiment.py \
  --allow-live --env-file Backend/.env \
  --catalogue docs/reviews/2026-11-20-meta-controller/pilot-catalogue.json \
  --output /tmp/meta-provider-calls.jsonl
```

```bash
adb install -r -t Frontend/app/build/outputs/apk/debug/app-debug.apk
adb install -r -t Frontend/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb reverse tcp:8766 tcp:8766
adb shell am instrument -w -r \
  -e class com.click.service.accessibility.MetaControllerDeviceExperimentTest#probeTaskSetups \
  -e metaSetupProbe true \
  com.click.metaexperiment.uitestdriver.test/androidx.test.runner.AndroidJUnitRunner
```

전체 시작 상태를 확인한 뒤 실행한다. 잘못된 초기화로 생긴 실패를 제품의 실패로 해석하지 않는다.

```bash
adb shell am instrument -w -r \
  -e class com.click.service.accessibility.MetaControllerDeviceExperimentTest#runNavigationExperiment \
  -e metaExperiment true -e liveMeta true -e arms A,B,C \
  -e conditions normal,noeffect,delay -e repeat 2 -e budgetMs 15000 \
  -e runId native-v4-20261120 -e clearOutput true \
  com.click.metaexperiment.uitestdriver.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.click.metaexperiment.uitestdriver \
  cat files/meta-controller/manifest.json > /tmp/meta-manifest.json
adb exec-out run-as com.click.metaexperiment.uitestdriver \
  cat files/meta-controller/episodes.jsonl > /tmp/meta-episodes.jsonl
```

`measurement-context.json`에 실제 기기·기반 revision·계측 소스 hash·설정 모델을 기록한다. 마지막 모델 요청이 종료되어 로그가 완결됐을 때만 `provider_capture_complete=true`로 설정한다. 불완전한 로그를 0회 호출로 바꾸지 않는다.

```bash
python3 Backend/scripts/join_meta_controller_measurements.py \
  --episodes /tmp/meta-episodes.jsonl --provider-calls /tmp/meta-provider-calls.jsonl \
  --context docs/reviews/2026-11-20-meta-controller/measurement-context.json \
  --output /tmp/meta-normalized.jsonl
python3 Backend/scripts/summarize_meta_controller_pilot.py \
  --manifest /tmp/meta-manifest.json --input /tmp/meta-normalized.jsonl \
  --output-dir /tmp/meta-results
```

집계기는 12개 과제·3조건·2반복·A/B/C의 정확한 216개 에피소드가 없으면 최종 요약을 거부한다. 대응 분석에서 시작 상태·기기·모델 설정·코드 hash가 다른 쌍은 제외하되 전체 시도 집계에는 남긴다.

끝나면 결과를 먼저 가져오고, 원래 접근성 설정이 복원됐는지 확인한 뒤 실험 패키지와 USB reverse를 정리한다. 테스트 앱을 강제 종료한 경우에는 finally가 실행되지 않을 수 있으므로 사전에 기록한 접근성 설정을 직접 복원해야 한다.
