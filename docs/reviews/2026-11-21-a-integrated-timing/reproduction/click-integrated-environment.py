from datetime import datetime,timezone
from pathlib import Path
import subprocess,json
root=Path('/Users/kimleewon/Desktop/Project/CLICK');out=root/'docs/reviews/2026-11-21-a-integrated-timing'
adb=['/Users/kimleewon/Library/Android/sdk/platform-tools/adb','-s','emulator-5560']
def shell(*args):return subprocess.check_output(adb+['shell',*args],text=True).strip()
keys=['ro.build.fingerprint','ro.build.version.sdk','ro.build.version.release','ro.hardware','ro.product.cpu.abi','ro.product.locale']
t0=datetime.now(timezone.utc);guest=shell('date','-u','+%Y-%m-%dT%H:%M:%SZ');t1=datetime.now(timezone.utc);mid=t0+(t1-t0)/2
r=dict(schema_version='click.goal-first-native-environment.v1',captured_at_utc=t1.isoformat(),serial='emulator-5560',avd_name='click_settings_retest_v2',accountless_emulator=True,isolated_application_id='com.click.metaexperiment.publicapps',android={k:shell('getprop',k) for k in keys},clock=dict(host_midpoint_utc=mid.isoformat(),guest_utc=guest,guest_minus_host_midpoint_seconds=round((datetime.fromisoformat(guest.replace('Z','+00:00'))-mid).total_seconds(),3),native_clock_rewritten=False),models={'navigation':'gemini-3.8-flash','gemini_completion':'gemini-3.8-flash','jev_completion':'jev-1.14.0'},coordinator='CURRENT/current only',planning_mode='goal_first',build_flags=dict(CLICK_APPLICATION_ID='com.click.metaexperiment.publicapps',CLICK_API_BASE_URL='http://127.0.0.1:8769',CLICK_CANDIDATE_DIAGNOSTICS=True,KAKAO_NATIVE_APP_KEY=''),scope='real native three-app component + A-only paired completion24 conditional12, no participants')
(out/'environment.json').write_text(json.dumps(r,indent=2)+'\n');print({'sdk':r['android']['ro.build.version.sdk'],'clock':r['clock']})
