import argparse,base64,shlex,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('method');p.add_argument('--serial',default='emulator-5560');p.add_argument('--flag',required=True);p.add_argument('--phase');p.add_argument('--conditions');p.add_argument('--append',action='store_true');p.add_argument('--log',required=True);a=p.parse_args()
assert a.serial.startswith('emulator-'), 'Use an isolated emulator, not a personal device'
root=Path(__file__).resolve().parents[3]
encoded=base64.b64encode((root/'experiments/metacontroller/native-settings-catalogue-v1.json').read_bytes()).decode()
remote=['am','instrument','-w','-e','class','com.click.service.accessibility.MetaControllerSettingsRetestTest#'+a.method,'-e',a.flag,'true','-e','catalogue64',encoded,'-e','runId','settings-native-v2-20261120']
if a.phase:remote+=['-e','phase',a.phase]
if a.conditions:remote+=['-e','conditionsJson',a.conditions]
if a.append:remote+=['-e','appendOutput','true']
remote+=['com.click.metaexperiment.settings.test/androidx.test.runner.AndroidJUnitRunner']
with open(a.log,'w') as out:
 result=subprocess.run(['/Users/kimleewon/Library/Android/sdk/platform-tools/adb','-s',a.serial,'shell',shlex.join(remote)],stdout=out,stderr=subprocess.STDOUT)
raise SystemExit(result.returncode)
