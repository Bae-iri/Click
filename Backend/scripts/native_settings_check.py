"""Release-bound opt-in physical Settings check. No screen leaves the device on a failed preflight."""
import argparse
import hashlib
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


def installed_app_hash(adb, package="com.click"):
    paths = subprocess.check_output([adb,'shell','pm','path',package],text=True).splitlines()
    path = next((p.removeprefix('package:') for p in paths if p.endswith('/base.apk')), None)
    if path is None:raise ValueError('Install the matching Click APK before validation')
    return subprocess.check_output([adb,'shell','sha256sum',path],text=True).split()[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='https://click-db.onrender.com')
    parser.add_argument('--run-live', action='store_true', help='Run six real native adjustments and restore all three original settings')
    parser.add_argument('--initial-position',choices=['top','bottom'],default='top',help='Settings home viewport before task start; never pre-position the target')
    parser.add_argument('--goal',choices=[s+'.'+d for s in ('font_size','brightness','volume') for d in ('increase','decrease')],help='Explicitly run only this goal; the report records this filter')
    parser.add_argument('--reuse-installed',action='store_true',help='Reuse only when both installed APK hashes exactly match --apk-dir')
    parser.add_argument('--apk-dir', type=Path, help='Frontend/app/build/outputs/apk containing signed compatible debug APKs')
    parser.add_argument('--report', type=Path, default=Path('/private/tmp/click-native-settings-stability.json'))
    args = parser.parse_args()
    build = verify_build(args.base_url)
    if not args.run_live:
        print(json.dumps({'preflight':'PASS','server_build':build}))
        return
    if args.apk_dir is None:raise ValueError('Physical verification requires --apk-dir with the prepared app and test APK pair')
    adb = shutil.which('adb') or str(Path(os.getenv('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb')
    devices = subprocess.check_output([adb, 'devices'], text=True).splitlines()[1:]
    if sum(line.endswith('\tdevice') for line in devices) != 1:
        raise ValueError('Connect exactly one authorized physical Android device')
    if args.apk_dir:
        for apk in (args.apk_dir/'debug/app-debug.apk', args.apk_dir/'androidTest/debug/app-debug-androidTest.apk'):
            if not apk.is_file():raise ValueError('Build both debug and androidTest APKs first')
            if not args.reuse_installed:subprocess.run([adb, 'install', '-r', str(apk)], check=True)
    before_apk = installed_app_hash(adb)
    before_test_apk = installed_app_hash(adb,"com.click.test")
    expected_test_apk = hashlib.sha256((args.apk_dir/'androidTest/debug/app-debug-androidTest.apk').read_bytes()).hexdigest()
    if before_test_apk != expected_test_apk:raise ValueError('Installed test APK is not the prepared build')
    expected_apk = hashlib.sha256((args.apk_dir/'debug/app-debug.apk').read_bytes()).hexdigest() if args.apk_dir else before_apk
    if before_apk != expected_apk:raise ValueError('Installed Click APK is not the prepared build')
    # Follow a real user launch after replacement installation, before native Settings.
    subprocess.run([adb,'shell','am','start','-W','-a','android.intent.action.MAIN',
        '-c','android.intent.category.LAUNCHER','-p','com.click'],check=True,capture_output=True,text=True)
    run_id = str(uuid4())
    focused = ['-e','onlyGoal',args.goal] if args.goal else []
    result = subprocess.run([adb,'shell','am','instrument','-w','-r',
        '-e','class','com.click.data.remote.LiveNativeSettingsStabilityTest',
        '-e','liveSettingsStability','true','-e','expectedSourceSha256',build['source_sha256'],
        '-e','validationRunId',run_id,'-e','initialPosition',args.initial_position,
        '-e','expectedAppApkSha256',expected_apk,'-e','expectedTestApkSha256',expected_test_apk,
        '-e','expectedBaseUrl',args.base_url.rstrip('/'),
        *focused,'com.click.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True,text=True)
    raw = subprocess.check_output([adb,'exec-out','run-as','com.click','cat','cache/native-settings-stability-result.json'])
    report = json.loads(raw)
    if report.get('run_id') != run_id:
        raise SystemExit('Device returned a stale report; this run did not pass')
    report['app_apk_sha256_before'] = before_apk
    report['app_apk_sha256_after'] = installed_app_hash(adb)
    report['test_apk_sha256_before'] = before_test_apk
    report['test_apk_sha256_after'] = installed_app_hash(adb,'com.click.test')
    report['app_build_stable'] = before_apk == report['app_apk_sha256_after'] and before_test_apk == report['test_apk_sha256_after']
    try:
        after = httpx.get(args.base_url.rstrip('/')+'/version',timeout=25)
        after.raise_for_status()
        body = after.json()
        report['server_build_after'] = body
        report['server_build_stable'] = (body.get('source_sha256') == build.get('source_sha256')
            and body.get('runtime_configuration') == build.get('runtime_configuration'))
    except (httpx.HTTPError,ValueError):
        report['server_build_stable'] = False
    report['instrumentation_ok'] = 'OK (1 test)' in result.stdout and result.returncode == 0
    report['passed'] = bool(report.get('passed')) and report['instrumentation_ok'] and report['app_build_stable'] and report['server_build_stable']
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'passed':report.get('passed',False),'report':str(args.report)},ensure_ascii=False))
    if not report.get('passed') or not report['instrumentation_ok']:
        raise SystemExit('Physical Settings suite failed; inspect its bounded report')


if __name__ == '__main__':
    main()
