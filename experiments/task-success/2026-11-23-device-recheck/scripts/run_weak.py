"""Run selected settings overlay tasks (repeat 0 = list top, repeat 1 = scrolled start) on the device."""
import json
import subprocess
import sys
from pathlib import Path

ADB = 'C:/click_server/.android-sdk/platform-tools/adb.exe'
SERIAL = 'R3CN8157M2Z'
PACKAGE = 'com.clueclick.taskstudy.deviceaudit'
WT = Path(sys.argv[1])
OUT = Path(sys.argv[2])
TASKS = sys.argv[3].split(',')
OUT.mkdir(parents=True, exist_ok=True)


def adb(*w):
    return subprocess.check_output([ADB, '-s', SERIAL, *w], timeout=900, text=True, encoding='utf8').strip()


def instrument(phase, method, flags):
    (OUT / 'phase').write_text(phase)
    words = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.click.data.remote.' + method]
    for k, v in flags.items():
        words += ['-e', k, str(v)]
    out = adb(*words, PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner')
    (OUT / (phase + '.log')).write_text(out, encoding='utf8')
    if 'OK (1 test)' not in out:
        raise RuntimeError('instrumentation failed in ' + phase)


apk = WT / 'Frontend/app/build/outputs/apk/debug/app-debug.apk'
test_apk = WT / 'Frontend/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
stay = adb('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in')
installed = []
try:
    for a, p in [(apk, PACKAGE), (test_apk, PACKAGE + '.test')]:
        adb('install', '-t', str(a)); installed.append(p)
    adb('reverse', 'tcp:8774', 'tcp:8774')
    adb('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '7')
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    instrument('setup', 'DeviceAuditSetupTest#prepareIsolatedAuditAccount', {'deviceAuditSetup': 'true'})
    for key in TASKS:
        phase = 'task-' + key
        instrument(phase, 'LiveOverlayAuditTest#fullProductMatrix',
                   {'deviceAuditSetup': 'true', 'deviceAuditOverlay': 'true', 'auditRepeats': 2, 'auditSet': 'all',
                    'auditTask': key, 'auditBudgetMs': 75000, 'auditAutoFromStart': 'false'})
        data = adb('shell', 'run-as', PACKAGE, 'cat', 'cache/device-audit-overlay.json')
        (OUT / (phase + '.json')).write_text(data, encoding='utf8')
        for c in json.loads(data)['cases']:
            print(key, c.get('repeat'), c.get('passed'), c.get('failure_code') or c.get('failure') or '',
                  c.get('goal_reached_ms'), flush=True)
finally:
    try:
        instrument('cleanup', 'DeviceAuditSetupTest#removeIsolatedAuditAccount', {'deviceAuditCleanup': 'true'})
    finally:
        adb('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', stay) if stay != 'null' else \
            adb('shell', 'settings', 'delete', 'global', 'stay_on_while_plugged_in')
        adb('reverse', '--remove', 'tcp:8774')
        for p in reversed(installed):
            adb('uninstall', p)
