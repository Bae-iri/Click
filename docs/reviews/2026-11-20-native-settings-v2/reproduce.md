# Native Settings v2 재현 절차

실행 소스는 **`0ed757454abae3696c4c48d24b51e05d2853f8d3`**다. [사전 프로토콜](../../../experiments/metacontroller/native-settings-protocol-v2.md), [환경](environment.json), [소스·APK 동결 기록](source-freeze.json), [전체 일정](manifest.json), [검증 기록](verification.json)을 함께 사용한다. 이 문서는 재현 명령이며 전체 테스트의 완료 여부나 새 결과를 대신하지 않는다.

**실제 등록 기록의 편차:** aggregate freeze helper가 `ai_agent` import에 두 번 실패했고, 그 뒤 정상 instrumentation이 먼저 시작됐다. `source-freeze.json`의 조립은 실행 시작 이후 끝났다. 당시 terminal 기록 0건은 진행 중인 모델 요청 0건을 의미하지 않는다. 다만 실행 소스 commit·프로토콜, 두 APK 해시, 환경과 전체 일정은 시작 전에 각각 고정·기록됐다. APK 해시는 2026-11-20 13:20:38 UTC의 도구 출력에 남았다. 이 차이를 완전한 호출 전 aggregate 동결로 표현하지 않는다. 재현 시에는 메타데이터 조립까지 먼저 끝내고 실제 시각을 기록한다.

## 환경과 빌드

기록된 환경은 Android 17/SDK 37 ARM64, `ranchu`, 영어 UI·한국어 목표, 계정 없는 `click_settings_retest_v2` AVD다. 격리 app ID는 `com.click.metaexperiment.settings`, 기록된 serial은 `emulator-5560`이다. 현재 측정 중인 에뮬레이터에 재설치하지 말고 새 인스턴스로 준비한다. 다른 serial은 아래 모든 명령과 helper의 `--serial`에 같은 값을 사용한다.

별도 checkout/worktree에 위 commit을 준비한다. 보고서 helper [run-instrument.py](run-instrument.py)는 보고 디렉터리를 기준으로 저장소를 찾으므로, 그 checkout의 같은 경로 `docs/reviews/2026-11-20-native-settings-v2/run-instrument.py`에 복사한다. helper는 고정 카탈로그를 base64로 전달하고 `shlex.join`으로 Android shell 인자를 인용한다. 아래 명령은 그 checkout의 루트에서 실행한다.

JDK 17과 프로젝트 Backend 의존성을 갖춘 Python을 사용한다. 이 호스트의 Backend Python은 `/private/tmp/click-stability-venv/bin/python`이다. SDK의 기록된 경로는 `/Users/kimleewon/Library/Android/sdk`이며 다른 호스트에서는 helper의 adb 경로를 호출 전에 맞추고 변경을 기록한다.

```bash
python3 - <<'PY'
from pathlib import Path
work=Path('/private/tmp/click-settings-v2-reproduction')
work.mkdir(exist_ok=False)
(work/'build.init.gradle').write_text("gradle.beforeProject { p -> p.layout.buildDirectory.set(new File('/private/tmp/click-settings-v2-reproduction/build', p.name)) }\n")
PY
bash Frontend/gradlew -p Frontend --no-daemon --console=plain -I /private/tmp/click-settings-v2-reproduction/build.init.gradle -PCLICK_APPLICATION_ID=com.click.metaexperiment.settings -PCLICK_API_BASE_URL=http://127.0.0.1:8768 -PCLICK_CANDIDATE_DIAGNOSTICS=true -PKAKAO_NATIVE_APP_KEY= :app:assembleDebug :app:assembleDebugAndroidTest
/Users/kimleewon/Library/Android/sdk/platform-tools/adb -s emulator-5560 install -r /private/tmp/click-settings-v2-reproduction/build/app/outputs/apk/debug/app-debug.apk
/Users/kimleewon/Library/Android/sdk/platform-tools/adb -s emulator-5560 install -r /private/tmp/click-settings-v2-reproduction/build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk
/Users/kimleewon/Library/Android/sdk/platform-tools/adb -s emulator-5560 reverse tcp:8768 tcp:8768
```

새 build의 source/APK 해시는 별도 재현 기록에 남긴다. 기존 기록의 APK 해시를 새 build에 그대로 붙이지 않는다. 생산 app `com.clueclick.app`을 설치·초기화 대상으로 사용하지 않는다.

## 모델 없는 준비와 전체 일정 고정

아래 helper의 run ID는 모든 단계에서 `settings-native-v2-20261120`으로 동일하다. 출력은 app의 `files/settings-meta-v2`다. 준비 검사와 guard의 `passed=true`, 여섯 목표의 체크, 동일한 `measurement_context_key`와 `measurement_revision=equal_independent_observation_window_v2`를 확인한다. instrumentation의 프로세스 종료 코드만으로 준비 성공을 판정하지 않는다.

```bash
python3 docs/reviews/2026-11-20-native-settings-v2/run-instrument.py validateNativeGuards --serial emulator-5560 --flag settingsGuardPreflight --phase guard --log /private/tmp/click-settings-v2-reproduction/guard.log
python3 docs/reviews/2026-11-20-native-settings-v2/run-instrument.py validateFrozenFixtures --serial emulator-5560 --flag settingsPreflight --phase readiness --log /private/tmp/click-settings-v2-reproduction/readiness.log
python3 docs/reviews/2026-11-20-native-settings-v2/run-instrument.py runSettingsMatrix --serial emulator-5560 --flag settingsExperiment --phase freeze --conditions '[]' --log /private/tmp/click-settings-v2-reproduction/freeze.log
```

빈 조건은 측정 에피소드를 실행하지 않고 36회·18쌍의 전체 manifest를 고정한다. 준비·일정 파일은 다음처럼 추출한다. 이후 추출에도 같은 `exec-out run-as` 방식과 새 파일명을 사용한다.

```bash
python3 - <<'PY'
from pathlib import Path
import json,shlex,subprocess
out=Path('/private/tmp/click-settings-v2-reproduction')
for name in ('readiness.json','guard-readiness.json','manifest.json','manifest-freeze.json'):
    remote=shlex.join(['run-as','com.click.metaexperiment.settings','cat','files/settings-meta-v2/'+name])
    data=subprocess.check_output(['/Users/kimleewon/Library/Android/sdk/platform-tools/adb','-s','emulator-5560','exec-out',remote])
    with (out/name).open('xb') as stream:stream.write(data)
ready=json.loads((out/'readiness.json').read_text());guard=json.loads((out/'guard-readiness.json').read_text())
schedule=json.loads((out/'manifest-freeze.json').read_text())
assert ready['passed'] is True and guard['passed'] is True
assert len(ready['checks'])==6 and all(item['ready'] is True for item in ready['checks'])
assert ready['measurement_context_key']==guard['measurement_context_key']==schedule['measurement_context_key']
assert schedule['execution_conditions']==[] and len(schedule['scheduled_pairs'])==18
PY
```

같은 카탈로그를 사용해도 날짜·라벨·환경이 달라 준비가 실패하면 새 cohort의 차이를 먼저 해결한다. 모델 결과를 본 뒤 목표·정답을 고치거나 시계를 바꿔 기존 기록에 맞추지 않는다. 준비가 끝나면 소스 commit/tree, APK 해시, 환경, 전체 manifest, 준비 자료의 해시와 실제 조립 시각을 정상 실행 전에 보관한다.

## Facade 실행

별도 터미널에서 실행한다. 실제 키는 프로세스 환경 또는 로컬 `Backend/.env`에서 읽으며 출력·복사하지 않는다. 별도 checkout에 `.env`가 없다면 이미 설정된 프로세스 환경을 사용한다. 로그는 기존 실행과 섞이지 않는 새 파일이다.

```bash
/private/tmp/click-stability-venv/bin/python - <<'PY'
from pathlib import Path
import os,runpy,sys
from dotenv import load_dotenv
root=Path.cwd();out=Path('/private/tmp/click-settings-v2-reproduction/provider-terminal.jsonl')
assert not out.exists(), 'Use a fresh cohort output'
load_dotenv(root/'Backend/.env',override=False)
assert os.environ.get('GEMINI_API_KEY'), 'Provide the key in the process environment or local Backend/.env'
os.environ['GEMINI_SETTINGS_MODEL']='gemini-3.8-flash'
script=root/'Backend/scripts/serve_meta_controller_settings_experiment.py'
sys.argv=[str(script),'--allow-live','--manifest',str(root/'experiments/metacontroller/native-settings-catalogue-v1.json'),'--port','8768','--output',str(out)]
runpy.run_path(str(script),run_name='__main__')
PY
```

전용 loopback facade의 로컬 시험 토큰은 `Bearer local-meta-settings-experiment`다. `/health`는 준비 응답이며 모델 연결 검사 결과가 아니다. 인증된 `/drain`은 진행 중 POST와 terminal 저장을 기다린다. helper의 빈 조건 고정은 facade가 시작된 후에도 수행할 수 있으나 정상 에피소드 시작 전에 전체 동결 기록을 끝낸다.

## 정상 12회 → 감사 → 장애 24회

```bash
python3 docs/reviews/2026-11-20-native-settings-v2/run-instrument.py runSettingsMatrix --serial emulator-5560 --flag settingsExperiment --phase normal --conditions '["normal"]' --log /private/tmp/click-settings-v2-reproduction/normal.log
curl --fail -H 'Authorization: Bearer local-meta-settings-experiment' http://127.0.0.1:8768/drain
```

정상 단계의 원본과 서버 snapshot을 보관한다. 이후 분석은 모델을 호출하지 않는다.

```bash
python3 - <<'PY'
from pathlib import Path
import shlex,subprocess
out=Path('/private/tmp/click-settings-v2-reproduction')
for source,target in {'episodes.jsonl':'episodes-normal.jsonl','manifest-normal.json':'manifest-normal.json'}.items():
    remote=shlex.join(['run-as','com.click.metaexperiment.settings','cat','files/settings-meta-v2/'+source])
    data=subprocess.check_output(['/Users/kimleewon/Library/Android/sdk/platform-tools/adb','-s','emulator-5560','exec-out',remote])
    with (out/target).open('xb') as stream:stream.write(data)
with (out/'provider-terminal-normal.jsonl').open('xb') as stream:
    stream.write((out/'provider-terminal.jsonl').read_bytes())
PY
```

```bash
/private/tmp/click-stability-venv/bin/python Backend/scripts/audit_meta_controller_settings_provider.py --episode-schema click.native-settings.v2 --episodes /private/tmp/click-settings-v2-reproduction/episodes-normal.jsonl --terminal /private/tmp/click-settings-v2-reproduction/provider-terminal-normal.jsonl --output /private/tmp/click-settings-v2-reproduction/request-audit-normal.json
/private/tmp/click-stability-venv/bin/python Backend/scripts/summarize_meta_controller_settings.py --episodes /private/tmp/click-settings-v2-reproduction/episodes-normal.jsonl --manifest /private/tmp/click-settings-v2-reproduction/manifest.json --readiness /private/tmp/click-settings-v2-reproduction/readiness.json --request-audit /private/tmp/click-settings-v2-reproduction/request-audit-normal.json --output /private/tmp/click-settings-v2-reproduction/summary-normal.json --markdown /private/tmp/click-settings-v2-reproduction/summary-normal.md
```

`normal_stratum_gate=PROCEED_TO_FROZEN_FAULT_STRATA`와 준비·guard·안전·대응 시작·시간·직접 요청 결합을 확인한 뒤 장애 조건을 추가한다. 분류된 모델/공급자 실패는 성능 결과로 남긴다. 안전·계측 문제로 중단하면 사유와 미실행 배정을 보존한다.

```bash
python3 docs/reviews/2026-11-20-native-settings-v2/run-instrument.py runSettingsMatrix --serial emulator-5560 --flag settingsExperiment --phase faults --conditions '["accepted_no_effect","delayed_transition"]' --append --log /private/tmp/click-settings-v2-reproduction/faults.log
curl --fail -H 'Authorization: Bearer local-meta-settings-experiment' http://127.0.0.1:8768/drain
```

같은 app 데이터·카탈로그·run ID·서버 파일·전체 일정을 유지한다. 정상 12회를 다시 실행하지 않는다. 최종 파일을 추출하고 전체 36회를 분석한다.

```bash
python3 - <<'PY'
from pathlib import Path
import shlex,subprocess
out=Path('/private/tmp/click-settings-v2-reproduction')
for name in ('episodes.jsonl','manifest-faults.json'):
    remote=shlex.join(['run-as','com.click.metaexperiment.settings','cat','files/settings-meta-v2/'+name])
    data=subprocess.check_output(['/Users/kimleewon/Library/Android/sdk/platform-tools/adb','-s','emulator-5560','exec-out',remote])
    with (out/name).open('xb') as stream:stream.write(data)
PY
```

```bash
/private/tmp/click-stability-venv/bin/python Backend/scripts/audit_meta_controller_settings_provider.py --episode-schema click.native-settings.v2 --episodes /private/tmp/click-settings-v2-reproduction/episodes.jsonl --terminal /private/tmp/click-settings-v2-reproduction/provider-terminal.jsonl --output /private/tmp/click-settings-v2-reproduction/request-audit.json
/private/tmp/click-stability-venv/bin/python Backend/scripts/summarize_meta_controller_settings.py --episodes /private/tmp/click-settings-v2-reproduction/episodes.jsonl --manifest /private/tmp/click-settings-v2-reproduction/manifest.json --readiness /private/tmp/click-settings-v2-reproduction/readiness.json --request-audit /private/tmp/click-settings-v2-reproduction/request-audit.json --output /private/tmp/click-settings-v2-reproduction/summary.json --markdown /private/tmp/click-settings-v2-reproduction/summary.md
```

v2는 공동 성공 전까지 독립 관찰을 30초 유지하고 첫 안정 도달·첫 검증·공동 성공을 구분한다. cleanup 뒤 목적 상태의 존재를 최초 도달로 해석하지 않는다. 미처리 dispatch 대기와 같은 화면 재시도 정책은 그대로다. 단일 앱·조건당 1회·스크립트 승인의 기술 비교이며 중장년층 참가자, 실기기 범용성, 기본 B 적용 GO를 검증하지 않는다. [v1 자료](../2026-11-20-native-settings/README.md)와의 차이는 공통 수정·context/capability·관찰 창·공급자 응답이 함께 달라진 기술 비교다.
