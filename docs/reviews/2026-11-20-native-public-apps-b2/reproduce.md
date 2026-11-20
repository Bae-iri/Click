# 재현 방법

실행 소스는 **`63ecb80a3ae22582bf37570df480ec95035b1fd2`**다. [source-freeze.json](source-freeze.json)의 소스·APK·준비 자료 해시, [environment.json](environment.json), [프로토콜](../../../experiments/metacontroller/native-public-apps-protocol-v1.md), [카탈로그](../../../experiments/metacontroller/native-public-apps-catalogue-v1.json)를 함께 사용한다. 사용자 변경을 보존한 별도 체크아웃과 별도 결과 보관 위치에서 재현한다. 보관된 원자료를 덮어쓰지 않는다.

실제 환경은 accountless `emulator-5560`, AVD `click_settings_retest_v2`, Android 17/API 37/arm64, `en-US`였다. 격리 app ID는 `com.click.metaexperiment.publicapps`, endpoint는 `http://127.0.0.1:8769`, 모델은 `gemini-3.8-flash`다. OS fingerprint가 같고 실제 세 앱 UI가 준비 검사를 통과해야 한다. APK 재빌드는 로컬 서명·도구 환경에 따라 해시가 달라질 수 있으므로 새 실행의 APK·환경을 다시 기록한다.

## 빌드와 모델 없는 준비

`Frontend`에서 실행한 빌드 속성은 다음과 같다. 앱과 test APK를 격리 에뮬레이터에 설치하고 `adb reverse tcp:8769 tcp:8769`를 설정한다. 기존 자료가 있는 앱 데이터를 재사용해 덮어쓰지 않는다.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
  -PCLICK_APPLICATION_ID=com.click.metaexperiment.publicapps \
  -PCLICK_API_BASE_URL=http://127.0.0.1:8769 \
  -PCLICK_CANDIDATE_DIAGNOSTICS=true -PKAKAO_NATIVE_APP_KEY=
```

아래는 실제 사용한 `/private/tmp/click-public-apps-instrument.py`의 수동 CLI에 대응하는 형태다. Python 리스트와 `shlex.join`으로 base64/JSON을 전달해 shell 문자열 해석을 제한한다. `run_id`는 새 코호트 이름이어야 한다. 이 동결 버전의 장치 출력은 `files/public-apps-meta-v1`로 고정돼 있으므로 재현용 격리 설치에서만 사용한다.

```python
import base64, json, shlex, subprocess
from pathlib import Path

root = Path.cwd()  # 저장소 루트
adb = str(Path.home() / "Library/Android/sdk/platform-tools/adb")
serial = "emulator-5560"
run_id = "native-public-apps-b2-reproduction"
catalogue = root / "experiments/metacontroller/native-public-apps-catalogue-v1.json"
encoded = base64.b64encode(catalogue.read_bytes()).decode("ascii")

def instrument(method, flag, phase=None, conditions=None, append=False):
    remote = ["am", "instrument", "-w", "-e", "class",
        "com.click.service.accessibility.MetaControllerPublicAppsMatrixTest#" + method,
        "-e", flag, "true", "-e", "catalogue64", encoded,
        "-e", "runId", run_id]
    if phase is not None:
        remote += ["-e", "phase", phase]
    if conditions is not None:
        remote += ["-e", "conditionsJson", json.dumps(conditions)]
    if append:
        remote += ["-e", "appendOutput", "true"]
    remote += ["com.click.metaexperiment.publicapps.test/androidx.test.runner.AndroidJUnitRunner"]
    subprocess.run([adb, "-s", serial, "shell", shlex.join(remote)], check=True)

instrument("validateFrozenFixtures", "publicAppsPreflight")
instrument("validateNativeGuards", "publicAppsGuardPreflight")
instrument("runPublicAppsMatrix", "publicAppsExperiment", "freeze", [])
```

각 probe의 상세 flags와 Files 관찰 절차는 [preparation-notes.md](preparation-notes.md)에 있다. readiness 6/6과 native guard가 같은 measurement context에서 통과한 뒤, 장치의 `readiness.json`, `guard-readiness.json`, `manifest.json`, `manifest-freeze.json`을 `adb exec-out run-as com.click.metaexperiment.publicapps cat files/public-apps-meta-v1/<파일>`로 보관한다. 전체 36회 일정, commit/tree, 코드·APK·환경·준비 해시를 **facade 시작 전에** 동결한다. 이 코호트는 [동결 기록](source-freeze.json)에 facade 미시작, loopback port 닫힘, 동결 전 관측 모델 시도 0회를 기록했다.

## 실제 공급자 실행

다음은 별도 터미널에서 facade를 시작하는 형태다. 이 단계부터 실제 모델 비용이 발생할 수 있다. 실제 실행 Python은 `/private/tmp/click-stability-venv/bin/python`이었으며, 의존성과 공급자 credentials가 준비된 동등한 환경으로 대체할 수 있다. `.env`는 메모리에 로드하고 값이나 credential을 출력하지 않는다. `terminal`은 새 빈 경로로 정한다.

```python
import runpy, sys
from pathlib import Path
from dotenv import load_dotenv

root = Path.cwd()
load_dotenv(root / "Backend/.env")
sys.path[:0] = [str(root / "Backend"), str(root / "Backend/scripts")]
terminal = Path("/private/tmp/new-public-apps-terminal.jsonl")
assert not terminal.exists(), "새 요청 로그 경로 필요"
sys.argv = ["serve_meta_controller_public_apps_experiment.py", "--allow-live",
    "--port", "8769", "--manifest",
    str(root / "experiments/metacontroller/native-public-apps-catalogue-v1.json"),
    "--output", str(terminal)]
runpy.run_path(str(root / "Backend/scripts/serve_meta_controller_public_apps_experiment.py"),
    run_name="__main__")
```

로컬 test token은 `local-meta-public-apps-experiment`이며 harness가 격리 설치에 설정한다. facade는 loopback만 열고 실제 공급자 서비스를 사용한다. 별도 instrument 터미널에서 먼저 정상 12회를 실행한다.

```python
instrument("runPublicAppsMatrix", "publicAppsExperiment", "normal", ["normal"])
```

정상 12회의 시작·oracle·native 안전·요청 직접 결합 gate를 평가한 뒤 같은 run ID와 전체 일정에 장애 24회를 추가한다. 정상 12회를 다시 실행하지 않는다.

```python
instrument("runPublicAppsMatrix", "publicAppsExperiment", "faults",
    ["accepted_no_effect", "delayed_transition"], append=True)
```

## 원자료와 분석

실제 보관 helper는 `/private/tmp/click-public-apps-pull.py`였다. `files/public-apps-meta-v1/episodes.jsonl`과 manifest/preflight 파일, `no_backup/app-task-diagnostics/{previous,current}.jsonl`, `no_backup/app-task-completion-diagnostics/{previous,current}.jsonl`, 호스트 facade terminal을 별도 결과 디렉터리로 수집한다. journal은 previous와 current를 순서대로 합치고 원본도 유지한다. native 시각은 호스트 시각으로 고쳐 쓰지 않는다.

저장소 루트에서 새 보관 디렉터리 `RESULT`를 지정해 다음 분석 CLI를 실행한다.

```sh
python Backend/scripts/audit_meta_controller_public_apps_provider.py \
  --episodes RESULT/episodes.jsonl --terminal RESULT/provider-terminal.jsonl \
  --output RESULT/request-audit.json
python Backend/scripts/summarize_meta_controller_public_apps.py \
  --episodes RESULT/episodes.jsonl --manifest RESULT/manifest.json \
  --readiness RESULT/readiness.json --request-audit RESULT/request-audit.json \
  --output RESULT/summary.json --markdown RESULT/summary.md
```

36회 전체 분모와 앱·조건별 결과, 실제 장애 노출, 실패 포함 시간, 직접 결합된 공급자 시도/unknown을 함께 확인한다. 완료·대기·복구 카운터를 모델 호출 수로 대체하지 않는다. 단일 에뮬레이터·세 앱·목표별 1회·scripted approval이며 참여자는 없다. 이전 Settings v2와의 비교는 앱·공통 코드·B 정책 등이 함께 바뀐 기술 비교이고, 특정 변경의 인과 효과나 범용 출시 GO를 입증하지 않는다.
