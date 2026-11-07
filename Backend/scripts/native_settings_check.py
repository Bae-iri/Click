"""Release-bound opt-in physical Settings check. No screen leaves the device on a failed preflight."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from uuid import uuid4
import httpx

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'Backend'), str(ROOT / 'AI-Agent/src')]
from app.core.validation import source_digest


def verify_build(base_url, *, get=httpx.get):
    response = get(base_url.rstrip('/') + '/version', timeout=25)
    response.raise_for_status()
    body = response.json()
    if body.get('target_validation') != 'settings-slider.v1' or body.get('source_sha256') != source_digest():
        raise ValueError('Server is not the tested release; deploy this Backend and AI-Agent before device checks')
    if body.get('runtime_configuration', {}).get('provider') != 'jev':
        raise ValueError('Physical Jev verification requires the production Jev provider')
    return body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='https://click-db.onrender.com')
    parser.add_argument('--run-live', action='store_true', help='Run six real native adjustments and restore all three original settings')
    parser.add_argument('--apk-dir', type=Path, help='Frontend/app/build/outputs/apk containing signed compatible debug APKs')
    parser.add_argument('--report', type=Path, default=Path('/private/tmp/click-native-settings-stability.json'))
    args = parser.parse_args()
    build = verify_build(args.base_url)
    if not args.run_live:
        print(json.dumps({'preflight':'PASS','server_build':build}))
        return
    adb = shutil.which('adb') or str(Path(os.getenv('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
    devices = subprocess.check_output([adb, 'devices'], text=True).splitlines()[1:]
    if sum(line.endswith('\tdevice') for line in devices) != 1:
        raise ValueError('Connect exactly one authorized physical Android device')
    if args.apk_dir:
        for apk in (args.apk_dir/'debug/app-debug.apk', args.apk_dir/'androidTest/debug/app-debug-androidTest.apk'):
            if not apk.is_file():raise ValueError('Build both debug and androidTest APKs first')
            subprocess.run([adb, 'install', '-r', str(apk)], check=True)
    run_id = str(uuid4())
    result = subprocess.run([adb,'shell','am','instrument','-w','-r',
        '-e','class','com.click.data.remote.LiveNativeSettingsStabilityTest',
        '-e','liveSettingsStability','true','-e','expectedSourceSha256',build['source_sha256'],
        '-e','validationRunId',run_id,
        '-e','expectedBaseUrl',args.base_url.rstrip('/'),
        'com.click.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True,text=True)
    raw = subprocess.check_output([adb,'exec-out','run-as','com.click','cat','cache/native-settings-stability-result.json'])
    report = json.loads(raw)
    if report.get('run_id') != run_id:
        raise SystemExit('Device returned a stale report; this run did not pass')
    report['instrumentation_ok'] = 'OK (1 test)' in result.stdout and result.returncode == 0
    report['passed'] = bool(report.get('passed')) and report['instrumentation_ok']
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'passed':report.get('passed',False),'report':str(args.report)},ensure_ascii=False))
    if not report.get('passed') or not report['instrumentation_ok']:
        raise SystemExit('Physical Settings suite failed; inspect its bounded report')


if __name__ == '__main__':
    main()
