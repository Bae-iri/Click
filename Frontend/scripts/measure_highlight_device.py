"""Run the opt-in native capture/overlay probe and restore its overlay app-op."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess


def overlay_mode(output):
    match = re.search(r'SYSTEM_ALERT_WINDOW:\s*(allow|ignore|deny|default|foreground)\b', output)
    if match:
        return match[1]
    if 'No operations' in output:
        return 'default'
    raise RuntimeError('Unable to determine existing overlay permission')


def measure(adb, serial, package, output):
    def run(*args, timeout=120):
        result = subprocess.run([adb, '-s', serial, *args], capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError('Device probe command failed: ' + args[0])
        return result.stdout

    original = overlay_mode(run('shell', 'cmd', 'appops', 'get', package, 'SYSTEM_ALERT_WINDOW'))
    report = None
    try:
        run('shell', 'run-as', package, 'rm', '-f', 'cache/highlight-latency-probe.json')
        run('shell', 'cmd', 'appops', 'set', package, 'SYSTEM_ALERT_WINDOW', 'allow')
        result = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'com.click.service.accessibility.HighlightLatencyProbeTest', '-e', 'highlightLatencyProbe', 'true',
            package + '.test/androidx.test.runner.AndroidJUnitRunner')
        if 'OK (1 test)' not in result:
            raise RuntimeError('Native probe assertions failed; check the device is unlocked and Settings is available')
        report = json.loads(run('exec-out', 'run-as', package, 'cat', 'cache/highlight-latency-probe.json'))
        if report.get('assertions_passed') is not True or len(report.get('samples', [])) != 20:
            raise RuntimeError('Native probe did not produce 20 verified samples')
    finally:
        run('shell', 'cmd', 'appops', 'set', package, 'SYSTEM_ALERT_WINDOW', original)
        restored = overlay_mode(run('shell', 'cmd', 'appops', 'get', package, 'SYSTEM_ALERT_WINDOW')) == original
        if report is not None:
            report['overlay_permission_restored'] = restored
            output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        if not restored:
            raise RuntimeError('Overlay permission restoration could not be verified')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='com.clueclick.app')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.adb:
        parser.error('Supply --adb or add Android platform-tools to PATH')
    report = measure(args.adb, args.serial, args.package, args.output)
    print(json.dumps({'samples': len(report['samples']), 'overlay_removed': report['overlay_removed'],
        'overlay_permission_restored': report['overlay_permission_restored']}))


if __name__ == '__main__':
    main()
