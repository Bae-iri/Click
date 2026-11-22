"""Run the paired route comparison and six app tasks on the attached Android device."""
import argparse
import json
from pathlib import Path
import subprocess
import urllib.request

PACKAGE = 'com.clueclick.taskstudy.deviceaudit'
CASES = [('graph','display'), ('free','settings_display'), ('free','settings_wifi'), ('graph','wifi'),
         ('free','clock_stopwatch'), ('free','youtube_shorts'), ('free','chrome_menu'),
         ('free','clock_timer'), ('free','chrome_settings'), ('free','youtube_search')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('apk','test-apk','output'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--cohort', choices=['main','commerce','terminal','auto','arrival'], default='main')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.glob('[12]-*.json')):
        parser.error('Choose a new output directory for each set of trials.')

    def adb(*words):
        return subprocess.check_output([args.adb,'-s',args.serial,*words], timeout=400, text=True).strip()

    def shell(*words):
        return adb('shell',*words)

    def instrument(phase, method, flags):
        (args.output/'phase').write_text(phase)
        words = ['am','instrument','-w','-r','-e','class','com.click.data.remote.'+method]
        for key,value in flags.items():
            words.extend(['-e',key,str(value)])
        output = shell(*words, PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner')
        (args.output/(phase+'.log')).write_text(output)
        if 'OK (1 test)' not in output or any('INSTRUMENTATION_STATUS_CODE: '+code in output for code in ('-3','-4')):
            raise RuntimeError('Instrumentation failed or skipped in '+phase)

    with urllib.request.urlopen('http://127.0.0.1:8774/health',timeout=20) as health:
        if json.load(health).get('status') != 'ok':
            raise RuntimeError('Start the isolated audit server and trace proxy first.')
    if shell('pm','list','packages',PACKAGE) or 'tcp:8774' in adb('reverse','--list'):
        raise RuntimeError('The test package or USB port is already in use.')
    stay = shell('settings','get','global','stay_on_while_plugged_in')
    original = shell('settings','get','secure','enabled_accessibility_services')
    installed = []
    reverse = False
    account = False
    try:
        for apk, pkg in [(args.apk,PACKAGE), (args.test_apk,PACKAGE+'.test')]:
            adb('install','-t',str(apk))
            installed.append(pkg)
        adb('reverse','tcp:8774','tcp:8774')
        reverse = True
        shell('settings','put','global','stay_on_while_plugged_in','7')
        shell('input','keyevent','KEYCODE_WAKEUP')
        instrument('setup','DeviceAuditSetupTest#prepareIsolatedAuditAccount',{'deviceAuditSetup':'true'})
        account = True
        cases = CASES if args.cohort == "main" else [("commerce","coupang_category")]
        schedules = (cases, list(reversed(cases)))
        if args.cohort == "terminal":
            schedules = ([('terminal','chrome_settings'), ('terminal','youtube_search')],)
        if args.cohort == "auto":
            schedules = ([('auto','display'), ('auto','wifi')],)
        if args.cohort == "arrival":
            schedules = ([('arrival','clock_stopwatch'), ('arrival','youtube_shorts'), ('routecheck','coupang_category')],
                         [('routecheck','coupang_category')])
        for rep, schedule in enumerate(schedules, 1):
            for arm,key in schedule:
                phase = f'{rep}-{arm}-{key}'
                graph = arm in ('graph','auto')
                queued = not graph and not key.startswith('settings_')
                instrument(phase,'LiveOverlayAuditTest#fullProductMatrix' if graph else 'LiveAppTaskAuditTest#realProviderNavigationMatrix',
                           {'deviceAuditSetup':'true', ('deviceAuditOverlay' if graph else 'deviceAuditApps'):'true',
                            'auditRepeats':1,'auditSet':'all','auditTask':key,'auditBudgetMs':75000,'auditAutoFromStart':str(arm=='auto').lower(),'auditViaQueue':str(queued).lower(),
                            'auditConfirmResult':str(arm in ('terminal','arrival')).lower()})
                file = 'overlay' if graph else 'app-tasks'
                data = shell('run-as',PACKAGE,'cat',f'cache/device-audit-{file}.json')
                cases = json.loads(data)['cases']
                if len(cases) != 1 or cases[0]['task'] != key:
                    raise RuntimeError('Missing or mismatched trial for '+phase)
                (args.output/(phase+'.json')).write_text(data)
                print(phase, cases[0]['passed'], flush=True)
    finally:
        try:
            if account:
                instrument('cleanup','DeviceAuditSetupTest#removeIsolatedAuditAccount',{'deviceAuditCleanup':'true'})
        finally:
            if stay == 'null':
                shell('settings','delete','global','stay_on_while_plugged_in')
            else:
                shell('settings','put','global','stay_on_while_plugged_in',stay)
            if reverse:
                adb('reverse','--remove','tcp:8774')
            for pkg in reversed(installed):
                adb('uninstall',pkg)
            if shell('settings','get','secure','enabled_accessibility_services') != original:
                raise RuntimeError('The accessibility service list differs after cleanup.')


if __name__ == '__main__':
    main()
