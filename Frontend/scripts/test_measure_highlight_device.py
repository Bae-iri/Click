import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from measure_highlight_device import measure


class Device:
    def __init__(self, succeeds):
        self.mode = 'ignore'
        self.succeeds = succeeds

    def run(self, command, **kwargs):
        args = command[3:]
        output = ''
        if args[:4] == ['shell', 'cmd', 'appops', 'get']:
            output = 'SYSTEM_ALERT_WINDOW: ' + self.mode
        elif args[:4] == ['shell', 'cmd', 'appops', 'set']:
            self.mode = args[-1]
        elif args[:3] == ['shell', 'am', 'instrument']:
            output = 'OK (1 test)' if self.succeeds else 'FAILURES!!! Tests run: 1'
        elif args[0] == 'exec-out':
            output = json.dumps({'assertions_passed': True, 'overlay_removed': True,
                'samples': [{} for _ in range(20)]})
        return subprocess.CompletedProcess(command, 0, stdout=output, stderr='')


class PermissionRestorationTest(unittest.TestCase):
    def test_success_restores_preexisting_permission_and_records_it(self):
        device = Device(True)
        with tempfile.TemporaryDirectory() as directory, patch('measure_highlight_device.subprocess.run', device.run):
            output = Path(directory) / 'result.json'
            result = measure('adb', 'fixture-device', 'fixture.app', output)
            self.assertEqual('ignore', device.mode)
            self.assertTrue(result['overlay_permission_restored'])
            self.assertTrue(json.loads(output.read_text())['overlay_removed'])

    def test_failed_instrumentation_restores_permission_without_publishing_success(self):
        device = Device(False)
        with tempfile.TemporaryDirectory() as directory, patch('measure_highlight_device.subprocess.run', device.run):
            output = Path(directory) / 'result.json'
            with self.assertRaises(RuntimeError):
                measure('adb', 'fixture-device', 'fixture.app', output)
            self.assertEqual('ignore', device.mode)
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
