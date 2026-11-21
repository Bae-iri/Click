import argparse,base64,shlex,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--policy',choices=['gemini','jev'],required=True);p.add_argument('--repeat',choices=['r1','r2','r3'],required=True);p.add_argument('--freeze',action='store_true');p.add_argument('--log',required=True);a=p.parse_args()
root=Path('/Users/kimleewon/Desktop/Project/CLICK');encoded=base64.b64encode((root/'experiments/metacontroller/native-public-apps-catalogue-v1.json').read_bytes()).decode();suffix='a-time-'+a.policy+'-20261121-'+a.repeat;run='native-'+suffix
remote=['am','instrument','-w','-e','class','com.click.service.accessibility.MetaControllerGoalFirstNativeTest#runGoalFirstNavigation6','-e','goalFirstNavigationExperiment','true','-e','catalogue64',encoded,'-e','runId',run,'-e','outputSuffix',suffix]
if a.freeze:remote+=['-e','goalFirstNavigationFreeze','true']
remote+=['com.click.metaexperiment.publicapps.test/androidx.test.runner.AndroidJUnitRunner']
with open(a.log,'w') as out:r=subprocess.run(['/Users/kimleewon/Library/Android/sdk/platform-tools/adb','-s','emulator-5560','shell',shlex.join(remote)],stdout=out,stderr=subprocess.STDOUT)
raise SystemExit(r.returncode)
