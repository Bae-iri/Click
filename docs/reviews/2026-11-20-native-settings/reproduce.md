# 실제 Android Settings 실험 재현

이 문서는 실행 절차이며 결과 보고서가 아니다. 전체 테스트가 완료됐다는 뜻으로 읽지 않는다. 측정 중인 실행에는 설치·초기화·재실행을 하지 말고, 새 계정 없는 에뮬레이터와 새 출력 경로를 사용한다. 기존 에피소드·서버 로그·준비 실패 기록을 보존한다.

기준은 [사전 프로토콜](../../../experiments/metacontroller/native-settings-protocol-v1.md), [카탈로그](../../../experiments/metacontroller/native-settings-catalogue-v1.json), [전체 일정](manifest.json), [환경](environment.json), [소스 동결 기록](source-freeze.json), [분석 소스 해시](analysis-source-sha256.json)다. 아래 명령은 저장소 루트에서 실행한다. 최종 develop에는 실험 후 원격 변경이 합쳐질 수 있으므로, 정확한 재현은 [통합 기록](integration.json)의 `frozen_source_commit`을 별도 worktree/checkout으로 선택한 뒤 진행한다. 현재 develop의 부분 소스 해시 일치만으로 전체 측정 APK와 같다고 판단하지 않는다.

## 고정 환경

- 기준 commit: `32041e5f79c5366be35d184e7b8b22657318c3da`와 `source-freeze.json`에 기록된 추가 소스. commit 번호만으로 신규 실험 파일을 대체하지 않는다.
- 이미지: `system-images;android-37.2;google_apis_playstore_ps16k;arm64-v8a`, Android 17, SDK 37, `ranchu`, ARM64, UI `en-US`. CPU 2개, RAM 2048MB, 1080×2400, 420dpi.
- 기록된 AVD: `click_settings_native`, `ANDROID_AVD_HOME=/private/tmp/click-settings-native-avd`, serial `emulator-5560`. 재현용 새 AVD는 별도 이름·디렉터리·port를 사용한다.
- 기록된 emulator flags: `-avd click_settings_native -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect -port 5560`.
- 격리 debug app: `com.click.metaexperiment.settings`, test app: `com.click.metaexperiment.settings.test`. 생산 앱 `com.clueclick.app`은 설치 대상으로 사용하지 않는다.
- Backend의 기록된 Python은 3.13, 실행 파일은 `/private/tmp/click-stability-venv/bin/python`이다. 다른 호스트에서는 `Backend/pyproject.toml`의 의존성을 갖춘 Python 3.11 이상 환경을 지정한다.
- 모델: `gemini-3.8-flash`, 환경 변수 `GEMINI_SETTINGS_MODEL`. 실제 키는 현재 프로세스 환경이나 로컬 `Backend/.env`에서 읽는다. 키를 명령줄·문서·실험 산출물에 기록하지 않는다.
- 6개 목표의 한국어 요청, 영어 Settings UI, 실제 접근성 관찰과 `AppTaskExecutor`를 사용한다. 관찰 시각·창 ID·신선도를 합성하거나 다시 쓰지 않는다.
- 예산 30,000ms, 첫 유효 TAP dispatch 지연 5,500ms, 조작 후 대기 750ms, 독립 목적 상태 안정 확인 700ms.

같은 이미지가 설치돼 있는지 SDK Manager로 확인한다. 새 AVD 생성 시 해당 이미지와 위 하드웨어 값을 지정한다. 처음부터 목적 화면이 떠 있거나 계정·키보드·locale 상태가 다르면 준비 검사를 통과시켜야 한다. 미래 날짜의 화면 라벨 등이 고정 카탈로그와 달라지면 시계를 조작해 맞추지 않는다. 모델 없이 새 카탈로그·프로토콜 버전과 별도 cohort를 준비하고 기존 실험과 구분한다.

## 소스 확인과 격리 APK 빌드

아래 검사는 현재 파일을 덮어쓰지 않고 기록된 해시와 비교한다.

```bash
python3 - <<'PY'
import hashlib, json
from pathlib import Path
root = Path.cwd()
review = root / 'docs/reviews/2026-11-20-native-settings'
frozen = json.loads((review / 'source-freeze.json').read_text())
analysis = json.loads((review / 'analysis-source-sha256.json').read_text())
for name, expected in {**frozen['source_sha256'], **analysis}.items():
    actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
    assert actual == expected, f'Source differs: {name}'
print('Recorded source hashes match')
PY
```

임시 build directory를 지정하고 진단 기록을 켠다. 아래 코드는 shell 문자열 보간 없이 인자를 전달한다.

```bash
python3 - <<'PY'
from pathlib import Path
import os, subprocess
root = Path.cwd()
work = Path('/private/tmp/click-native-settings-reproduction')
work.mkdir(exist_ok=False)
init = work / 'build.init.gradle'
init.write_text("gradle.beforeProject { p -> p.layout.buildDirectory.set(new File('/private/tmp/click-native-settings-reproduction/build', p.name)) }\n")
env = os.environ.copy()
env['JAVA_HOME'] = subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip()
subprocess.run(['bash', './gradlew', '--no-daemon', '--console=plain', '-I', str(init),
    '-PCLICK_APPLICATION_ID=com.click.metaexperiment.settings',
    '-PCLICK_API_BASE_URL=http://127.0.0.1:8768',
    '-PCLICK_CANDIDATE_DIAGNOSTICS=true', '-PKAKAO_NATIVE_APP_KEY=',
    ':app:assembleDebug', ':app:assembleDebugAndroidTest'], cwd=root/'Frontend', env=env, check=True)
PY
```

새 AVD를 위 이미지와 하드웨어로 만든다. 이미지 설치가 필요하면 SDK Manager에 위의 정확한 package ID를 지정한다. 다음은 설치된 이미지를 사용하는 예시다. 기록된 startup flags에서 새 AVD 이름·port만 바꾸며, 새 serial은 `emulator-5562`다.

```bash
python3 - <<'PY'
from pathlib import Path
import os, subprocess
sdk=Path(os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'Library/Android/sdk')))
work=Path('/private/tmp/click-native-settings-reproduction')
avds=work/'avd';avds.mkdir(exist_ok=False)
env=os.environ.copy();env['ANDROID_AVD_HOME']=str(avds)
env['JAVA_HOME']=subprocess.check_output(['/usr/libexec/java_home','-v','17'],text=True).strip()
subprocess.run([str(sdk/'cmdline-tools/latest/bin/avdmanager'),'create','avd',
    '-n','click_settings_native_repro','-k','system-images;android-37.2;google_apis_playstore_ps16k;arm64-v8a',
    '-d','pixel_8'],input='no\n',text=True,env=env,check=True)
config=avds/'click_settings_native_repro.avd/config.ini'
lines=config.read_text().splitlines()
values={'hw.cpu.ncore':'2','hw.ramSize':'2048','hw.lcd.width':'1080','hw.lcd.height':'2400','hw.lcd.density':'420'}
lines=[line for line in lines if line.split('=',1)[0] not in values]
config.write_text('\n'.join(lines+[key+'='+value for key,value in values.items()])+'\n')
log=(work/'emulator.log').open('x')
subprocess.Popen([str(sdk/'emulator/emulator'),'-avd','click_settings_native_repro',
    '-no-window','-no-audio','-no-snapshot','-no-boot-anim','-gpu','swiftshader_indirect','-port','5562'],
    env=env,stdout=log,stderr=subprocess.STDOUT)
PY
```

부팅과 초기 설정을 끝낸 뒤 설치한다. 인증 계정은 추가하지 않는다. 이미지나 device profile을 사용할 수 없으면 다른 이미지로 조용히 대체하지 않고 새 환경의 차이를 기록한다.

```bash
python3 - <<'PY'
from pathlib import Path
import os, subprocess
sdk = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home()/'Library/Android/sdk')))
adb = str(sdk/'platform-tools/adb')
serial = 'emulator-5562'
build = Path('/private/tmp/click-native-settings-reproduction/build/app/outputs/apk')
for apk in (build/'debug/app-debug.apk', build/'androidTest/debug/app-debug-androidTest.apk'):
    subprocess.run([adb, '-s', serial, 'install', '-r', str(apk)], check=True)
subprocess.run([adb, '-s', serial, 'reverse', 'tcp:8768', 'tcp:8768'], check=True)
PY
```

## 안전한 instrumentation 인자 전달

다음 helper는 JSON과 base64 카탈로그를 인자 목록으로 만들고, Android shell 경계에서만 `shlex.join`으로 인용한다. `conditionsJson`의 `[]`와 따옴표를 잃지 않는다. 아래에서 만드는 helper와 출력은 모두 새 임시 경로에 둔다.

```bash
python3 - <<'PY'
from pathlib import Path
work = Path('/private/tmp/click-native-settings-reproduction')
helper = work / 'instrument.py'
helper.write_text(r'''import argparse, base64, os, shlex, subprocess
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('method', choices=['probePublicSettings','validateNativeGuards','validateFrozenFixtures','runSettingsMatrix'])
p.add_argument('--conditions');p.add_argument('--phase', default='prepare');p.add_argument('--append', action='store_true')
a=p.parse_args();root=Path.cwd();work=Path('/private/tmp/click-native-settings-reproduction')
sdk=Path(os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'Library/Android/sdk')))
flags={'probePublicSettings':'settingsProbe','validateNativeGuards':'settingsGuardPreflight',
       'validateFrozenFixtures':'settingsPreflight','runSettingsMatrix':'settingsExperiment'}
encoded=base64.b64encode((root/'experiments/metacontroller/native-settings-catalogue-v1.json').read_bytes()).decode()
remote=['am','instrument','-w','-e','class','com.click.service.accessibility.MetaControllerSettingsExperimentTest#'+a.method,
        '-e',flags[a.method],'true','-e','catalogue64',encoded,'-e','runId','settings-native-v1-20261120',
        '-e','phase',a.phase]
if a.conditions is not None:remote+=['-e','conditionsJson',a.conditions]
if a.append:remote+=['-e','appendOutput','true']
remote+=['com.click.metaexperiment.settings.test/androidx.test.runner.AndroidJUnitRunner']
log=work/(a.phase+'-'+a.method+'.log')
with log.open('x') as stream:
    subprocess.run([str(sdk/'platform-tools/adb'),'-s','emulator-5562','shell',shlex.join(remote)],
        stdout=stream,stderr=subprocess.STDOUT,check=True)
text=log.read_text()
assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text, str(log)
print(log)
''')
pull=work/'pull.py'
pull.write_text(r'''import os, re, shlex, subprocess, sys
from pathlib import Path
sdk=Path(os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'Library/Android/sdk')))
out=Path('/private/tmp/click-native-settings-reproduction')
for spec in sys.argv[1:]:
    source,separator,target=spec.partition(':')
    if not separator:target=source
    assert all(re.fullmatch(r'[A-Za-z0-9_.-]+',name) for name in (source,target))
    command=shlex.join(['run-as','com.click.metaexperiment.settings','cat','files/settings-meta/'+source])
    data=subprocess.check_output([str(sdk/'platform-tools/adb'),'-s','emulator-5562','exec-out',command])
    with (out/target).open('xb') as stream:stream.write(data)
    print(out/target)
''')
print(helper)
PY
```

여기까지는 `GEMINI_API_KEY`가 필요 없다. 아래 준비 테스트는 모델을 호출하지 않는다. `am instrument`의 종료 코드만으로 준비 성공을 판단하지 않는다. 추출한 JSON의 `passed=true`, 전체 체크, 동일한 `measurement_context_key`를 확인한다.

```bash
python3 /private/tmp/click-native-settings-reproduction/instrument.py probePublicSettings --phase probe
python3 /private/tmp/click-native-settings-reproduction/instrument.py validateNativeGuards --phase guard
python3 /private/tmp/click-native-settings-reproduction/instrument.py validateFrozenFixtures --phase readiness
```

준비 파일은 `run-as`로 추출한다. 이후에도 같은 방법으로 `manifest.json`, `episodes.jsonl`을 가져온다.

```bash
python3 /private/tmp/click-native-settings-reproduction/pull.py probe.json guard-readiness.json readiness.json
python3 - <<'PY'
from pathlib import Path
import json
out=Path('/private/tmp/click-native-settings-reproduction')
ready=json.loads((out/'readiness.json').read_text());guard=json.loads((out/'guard-readiness.json').read_text())
assert ready['passed'] is True and guard['passed'] is True
assert len(ready['checks'])==6 and all(row['ready'] is True for row in ready['checks'])
assert ready['measurement_context_key']==guard['measurement_context_key']
assert ready['model_calls']==guard['model_calls']==0
PY
```

## Facade와 빈 조건으로 전체 일정 고정

Backend는 기존 서비스를 `memory=None`으로 사용하는 전용 loopback endpoint다. 로컬 시험용 Bearer는 `local-meta-settings-experiment`다. `/health`는 모델 연결을 검사하지 않는다. 인증된 `/drain`은 진행 중인 POST와 terminal 저장을 기다린다. 이 토큰은 생산 서비스의 로그인을 대신하지 않는다.

별도 터미널에서 아래를 실행하고 측정 종료까지 유지한다. 기존 서버 출력에 이어 쓰지 않는다. `load_dotenv`는 비밀을 출력하지 않으며 기존 프로세스 환경을 덮어쓰지 않는다.

```bash
/private/tmp/click-stability-venv/bin/python - <<'PY'
import os, runpy, sys
from pathlib import Path
from dotenv import load_dotenv
root=Path.cwd();out=Path('/private/tmp/click-native-settings-reproduction/server-terminal.jsonl')
assert not out.exists(), 'Use a fresh server output'
load_dotenv(root/'Backend/.env', override=False)
assert os.environ.get('GEMINI_API_KEY'), 'Supply GEMINI_API_KEY in the process environment or local Backend/.env'
os.environ['GEMINI_SETTINGS_MODEL']='gemini-3.8-flash'
script=root/'Backend/scripts/serve_meta_controller_settings_experiment.py'
sys.argv=[str(script),'--allow-live','--port','8768','--manifest',
          str(root/'experiments/metacontroller/native-settings-catalogue-v1.json'),'--output',str(out)]
runpy.run_path(str(script),run_name='__main__')
PY
```

측정용 터미널에서 빈 조건을 전달해 36회 전체 manifest를 모델 요청 전에 고정한다. 에피소드는 실행하지 않는다.

```bash
python3 /private/tmp/click-native-settings-reproduction/instrument.py runSettingsMatrix --phase freeze --conditions '[]'
python3 /private/tmp/click-native-settings-reproduction/pull.py manifest.json manifest-freeze.json
python3 - <<'PY'
import json
from pathlib import Path
out=Path('/private/tmp/click-native-settings-reproduction')
frozen=json.loads((out/'manifest-freeze.json').read_text())
assert frozen['execution_conditions']==[] and len(frozen['scheduled_pairs'])==18
assert (out/'server-terminal.jsonl').read_bytes()==b''
PY
```

이 시점에 `manifest.json`과 `manifest-freeze.json`을 추출해 `execution_conditions=[]`, 전체 18쌍·36회 일정, 서버 terminal 0건, 측정 에피소드 미생성을 확인한다. 새 source/APK/준비 자료의 SHA256과 환경·모델명도 별도 cohort 출력에 저장한다. 기존 `environment.json`, `source-freeze.json`을 수정하지 않는다.

## 정상 12회 → 감사 → 장애 24회

정상 12회를 한 번만 실행한다. 이 12회는 전체 36회에 포함된다. 실패·타임아웃 결과를 보고 같은 조건을 다시 실행하거나 목표·정답을 변경하지 않는다.

```bash
python3 /private/tmp/click-native-settings-reproduction/instrument.py runSettingsMatrix --phase normal --conditions '["normal"]'
python3 /private/tmp/click-native-settings-reproduction/pull.py episodes.jsonl:episodes-normal.jsonl manifest-normal.json
```

정상 단계의 원시 에피소드는 `episodes-normal.jsonl`로 보존한다. 전체 manifest는 freeze 단계에서 이미 추출했다. 서버 기록과 callback이 정리됐는지 확인하고 다음 명령으로 직접 결합과 정상 단계 감사를 수행한다. Harness가 에피소드마다 인증된 `/drain`을 실행하며, 서버 상태는 아래와 같이 별도로 확인할 수 있다.

```bash
python3 - <<'PY'
import json, urllib.request
from pathlib import Path
request=urllib.request.Request('http://127.0.0.1:8768/drain',headers={'Authorization':'Bearer local-meta-settings-experiment'})
with urllib.request.urlopen(request,timeout=13) as response:
    result=json.load(response)
assert result['terminal_receipts_complete'] is True
out=Path('/private/tmp/click-native-settings-reproduction')
with (out/'server-terminal-normal.jsonl').open('xb') as stream:
    stream.write((out/'server-terminal.jsonl').read_bytes())
print(result['status'])
PY
```

```bash
/private/tmp/click-stability-venv/bin/python Backend/scripts/audit_meta_controller_settings_provider.py --episodes /private/tmp/click-native-settings-reproduction/episodes-normal.jsonl --terminal /private/tmp/click-native-settings-reproduction/server-terminal-normal.jsonl --output /private/tmp/click-native-settings-reproduction/normal-provider-audit.json
/private/tmp/click-stability-venv/bin/python Backend/scripts/summarize_meta_controller_settings.py --episodes /private/tmp/click-native-settings-reproduction/episodes-normal.jsonl --manifest /private/tmp/click-native-settings-reproduction/manifest.json --readiness /private/tmp/click-native-settings-reproduction/readiness.json --request-audit /private/tmp/click-native-settings-reproduction/normal-provider-audit.json --output /private/tmp/click-native-settings-reproduction/normal-summary.json --markdown /private/tmp/click-native-settings-reproduction/normal-summary.md
```

`normal_stratum_gate=PROCEED_TO_FROZEN_FAULT_STRATA`와 준비·guard·시작 상태·요청 결합·안전 조건을 확인한 뒤 진행한다. 분류된 모델/공급자 실패는 성능 결과로 보존한다. 안전·계측 문제로 중단하면 중단 이유와 미실행 24회를 남긴다.

```bash
python3 /private/tmp/click-native-settings-reproduction/instrument.py runSettingsMatrix --phase faults --conditions '["accepted_no_effect","delayed_transition"]' --append
python3 /private/tmp/click-native-settings-reproduction/pull.py episodes.jsonl manifest-faults.json
/private/tmp/click-stability-venv/bin/python Backend/scripts/audit_meta_controller_settings_provider.py --episodes /private/tmp/click-native-settings-reproduction/episodes.jsonl --terminal /private/tmp/click-native-settings-reproduction/server-terminal.jsonl --output /private/tmp/click-native-settings-reproduction/provider-audit.json
/private/tmp/click-stability-venv/bin/python Backend/scripts/summarize_meta_controller_settings.py --episodes /private/tmp/click-native-settings-reproduction/episodes.jsonl --manifest /private/tmp/click-native-settings-reproduction/manifest.json --readiness /private/tmp/click-native-settings-reproduction/readiness.json --request-audit /private/tmp/click-native-settings-reproduction/provider-audit.json --output /private/tmp/click-native-settings-reproduction/summary.json --markdown /private/tmp/click-native-settings-reproduction/summary.md
```

동일한 app 데이터, run ID, 카탈로그, 전체 manifest, 서버 출력을 유지한다. `--append`는 장애 24회 추가에만 사용한다. 전체 36회의 `episodes.jsonl`을 정상 단계 snapshot과 함께 보존한다. 정상 12회를 중복시키지 않는다. 중복·누락·대응하지 않는 시작 상태는 분석기가 문제로 남긴다.

## 해석과 보존 범위

주 지표는 30초 안에 독립 목적 상태 안정, 현재 유효한 앱 검증, 미처리 native 조작 0건이 함께 성립한 경우다. 단순 목적 화면 도달과 구분한다. 실패·불명확·미실행을 배정된 36회에서 제외하지 않는다. 일찍 실패한 경우에도 공통 30초를 부여한다.

무효 조건은 첫 유효 TAP을 수락하되 효과를 주지 않고, 지연 조건은 실제 dispatch를 5.5초 늦춘다. 앱의 렌더링 자체를 지연하는 실험과 구분한다. 지연을 예약했더라도 후속 native 검사에서 거절되면 실현된 장애 노출로 세지 않는다. 예산 이후 native/provider 정리 시간과 늦은 도달을 별도로 기록하고 예산 내 성공으로 바꾸지 않는다.

직접 감사는 요청·작업 해시를 대조한다. 서비스 진입 전 0회, 확인된 모델 호출, 불명확을 구분하며 모델 실행 후 HTTP 422도 비용에 포함한다. 조건별 집계도 `audited_*`를 사용하고 클라이언트 전용 카운터와 혼동하지 않는다. 독립 정답은 별도 로직으로 정의하지만 OS 접근성 데이터를 공유하므로 이미지 기반 독립 판정을 수행했다고 주장하지 않는다.

단일 앱·1회 반복·스크립트 승인의 기술 검증이며 사람의 최종 확인을 자동으로 대신하지 않는다. 보호용 test APK를 두 번째 벤치마크 앱으로 세지 않는다. 실기기·중장년층 참가자·여러 앱의 일반화·통계적 우월·B 기본 적용 GO의 근거로 삼지 않는다. 원시 에피소드와 terminal, 준비 기록, 전체·단계별 일정, 소스/분석/APK 해시, 환경, 감사와 요약을 동일 cohort로 보관한다.
