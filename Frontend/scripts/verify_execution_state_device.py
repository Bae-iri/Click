"""Opt-in USB device probe; restores connectivity and previously enabled Click accessibility.

Build/install app + androidTest APKs first. This writes isolated fixture records only;
no fixture event is sent to the server and no account/auth data is cleared.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--adb',default=shutil.which('adb'))
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--application-id',default='com.clueclick.app')
    args=parser.parse_args()
    component=args.application_id+'/com.click.service.accessibility.ClickAccessibilityService'
    service_prefix=args.application_id+'/'
    if not args.adb:parser.error('Supply --adb or add Android platform-tools to PATH')
    report={'scope':'encrypted_device_journal_and_local_controller','server_events_sent':False,
            'production_end_to_end':False,'application_id':args.application_id,'steps':[]}
    logs=[]
    def adb(*command):
        result=subprocess.run([args.adb,'-s',args.serial,*command],capture_output=True,text=True,timeout=90)
        logs.append(' '.join(command)+'\n'+result.stdout+result.stderr)
        if result.returncode:raise RuntimeError('ADB failed: '+' '.join(command[:3]))
        return result.stdout.strip()
    def phase(name):
        text=adb('shell','am','instrument','-w','-e','class','com.click.service.overlay.ExecutionStateDeviceTest',
                 '-e','executionContractProbe',name,args.application_id+'.test/androidx.test.runner.AndroidJUnitRunner')
        if 'OK (1 test)' not in text or 'FAILURES!!!' in text:raise RuntimeError('Device probe failed: '+name)
        report['steps'].append({'phase':name,'passed':True})
        print(name+': passed',flush=True)
    wifi=adb('shell','settings','get','global','wifi_on')
    enabled=adb('shell','settings','get','secure','enabled_accessibility_services')
    prior_click=any(x.startswith(service_prefix) for x in enabled.split(':'))
    report.update(wifi_before=wifi,click_accessibility_before=prior_click)
    try:
        phase('stage')
        adb('shell','am','force-stop',args.application_id)
        report['process_restarted']=True
        adb('shell','svc','wifi','disable')
        for _ in range(30):
            if adb('shell','settings','get','global','wifi_on')=='0':break
            time.sleep(.2)
        phase('verify')
        report['passed']=True
    except Exception as failed:
        report.update(passed=False,error=str(failed))
        raise
    finally:
        errors=[]
        def restore_wifi():
            adb('shell','svc','wifi','enable' if wifi=='1' else 'disable')
            for _ in range(30):
                if adb('shell','settings','get','global','wifi_on')==wifi:break
                time.sleep(.2)
            report['wifi_after']=adb('shell','settings','get','global','wifi_on')
        def restore_accessibility():
            current=adb('shell','settings','get','secure','enabled_accessibility_services')
            services=[] if current in ('null','') else current.split(':')
            if prior_click and not any(x.startswith(service_prefix) for x in services):
                services.append(component)
                adb('shell','settings','put','secure','enabled_accessibility_services',':'.join(services))
                adb('shell','settings','put','secure','accessibility_enabled','1')
            report['click_accessibility_after']=any(x.startswith(service_prefix) for x in
                adb('shell','settings','get','secure','enabled_accessibility_services').split(':'))
        # Each cleanup is independent: a failed fixture cleanup must not skip restoration.
        for name,operation in [('wifi',restore_wifi),('fixture',lambda:phase('cleanup')),('accessibility',restore_accessibility)]:
            try:operation()
            except Exception as failed:errors.append({'step':name,'error':str(failed)})
        report['restored']=report.get('wifi_after')==wifi and report.get('click_accessibility_after')==prior_click
        if errors:report.update(cleanup_errors=errors,passed=False)
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,indent=2)+'\n')
        args.output.with_suffix('.log').write_text('\n'.join(logs))
    if not report.get('restored'):raise RuntimeError('Inspect device restoration report')
    print('Wi-Fi and prior Click accessibility restored.',flush=True)

if __name__=='__main__':main()
