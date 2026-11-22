"""Sequential paired Settings diagnosis; never operates two instrumentation sessions together."""
import argparse,json,subprocess,time,re,hashlib
from pathlib import Path

PACKAGE='com.clueclick.scrollstudy.deviceaudit'

def main():
 p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--tasks',default='display,wifi');p.add_argument('--rounds',type=int,default=1);p.add_argument('--arms',default='baseline,retry');p.add_argument('--resume',action='store_true');a=p.parse_args()
 a.output.mkdir(parents=True,exist_ok=True)
 def adb(*args,timeout=180):return subprocess.check_output(['adb','-s',a.serial,*args],timeout=timeout)
 def shell(*args,timeout=180):return adb('shell',*map(str,args),timeout=timeout).decode().strip()
 def instrument(method,flags):
  words=['am','instrument','-w','-r','-e','class','com.click.data.remote.'+method]
  for k,v in flags.items():words+=['-e',k,str(v)]
  return shell(*words,PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner',timeout=180)
 original=shell('settings','get','secure','enabled_accessibility_services')
 ports={'baseline':8765,'retry':8768,'guarded':8769,'guarded_v2':8770}
 try:
  for round in range(a.rounds):
   tasks=a.tasks.split(',');arms=a.arms.split(',')
   for i,task in enumerate(tasks):
    order=arms if (round+i)%2==0 else list(reversed(arms))
    for arm in order:
     label=f'r{round+1}-{arm}-{task}';out=a.output/label
     if out.exists():
      if a.resume and (out/'result.json').exists():print('preserved',label,flush=True);continue
      raise RuntimeError('Output already exists: '+label)
     out.mkdir();adb('reverse','tcp:8774','tcp:'+str(ports[arm]))
     flags={'deviceAuditSetup':'true','deviceAuditResetAccount':'true','deviceAuditOverlay':'true','auditJudgmentReady':'true','auditTask':task,'auditRepeats':1,'auditQueryIndex':round%2,'auditInitialScroll':str(round%2==1).lower(),'auditBudgetMs':75000}
     try:
      log=instrument('LiveOverlayAuditTest#fullProductMatrix',flags);(out/'instrumentation.log').write_text(log)
      raw=adb('exec-out','run-as',PACKAGE,'cat','cache/device-audit-overlay.json');data=json.loads(raw)
      if len(data['cases'])!=1 or data['cases'][0]['task']!=task:raise RuntimeError('Mismatched case')
      row=data['cases'][0]
      record={'arm':arm,'round':round,'query_index':round%2,'initial_scroll':round%2==1,'measurement':'visible_judgment_candidate_before_product_completion','instrumentation_ok':'OK (1 test)' in log,'android':row}
      (out/'result.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
      for name in ('evidence_first','evidence_last'):
       path=row.get(name)
       if path and re.fullmatch(r'judgment-evidence/[a-f0-9-]+\.png',path):
        (out/(name+'.png')).write_bytes(adb('exec-out','run-as',PACKAGE,'cat','cache/'+path))
      try:pid=shell('pidof',PACKAGE)
      except subprocess.CalledProcessError:
       info=shell('dumpsys','activity','exit-info',PACKAGE);match=re.search(r'pid=(\d+)',info);pid=match[1] if match else ''
      if pid:
       (out/'device.log').write_bytes(adb('logcat','-d','--pid='+pid.split()[0],'-v','epoch','-s','ClickScroll:I','ClickAutomation:I','ClickAgent:I','ClickSnapshot:I','ClickCandidates:I'))
      print(label,'native',row.get('native_goal'),'ready_candidate',row.get('judgment_ready_candidate'),'ms',row.get('time_to_judgment_ready_ms'),'failure',row.get('failure_code') or row.get('failure'),flush=True)
     except subprocess.TimeoutExpired as error:
      (out/'instrumentation-timeout.log').write_bytes(error.output or b'')
      (out/'infra-error.json').write_text(json.dumps({'stage':'instrumentation','host_timeout_seconds':error.timeout,'completed_result_available':False,'policy_outcome':'not_classified'},indent=2))
      try:(out/'last-cached-progress.json').write_bytes(adb('exec-out','run-as',PACKAGE,'cat','cache/device-audit-overlay.json'))
      except Exception:pass
      raise
     finally:
      try:(out/'cleanup.log').write_text(instrument('DeviceAuditSetupTest#removeIsolatedAuditAccount',{'deviceAuditCleanup':'true'}))
      except Exception as e:(out/'cleanup-error.json').write_text(json.dumps({'type':type(e).__name__}))
 finally:
  shell('settings','put','secure','enabled_accessibility_services',original)
if __name__=='__main__':main()
