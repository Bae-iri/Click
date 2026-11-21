from pathlib import Path
from datetime import datetime,timezone
import argparse,subprocess,json,hashlib,urllib.request
p=argparse.ArgumentParser();p.add_argument('--block',required=True);a=p.parse_args()
root=Path('/Users/kimleewon/Desktop/Project/CLICK');base=root/'docs/reviews/2026-11-21-a-integrated-timing';out=base/'blocks'/a.block
manifest=json.loads((base/'manifest.json').read_text());block=next(x for x in manifest['blocks'] if x['block_id']==a.block)
req=urllib.request.Request('http://127.0.0.1:8769/drain',headers={'Authorization':'Bearer local-meta-public-apps-experiment'})
with urllib.request.urlopen(req,timeout=30) as r:drain=json.loads(r.read())
assert drain['status']=='idle' and drain['terminal_receipts_complete'] is True
adb=['/Users/kimleewon/Library/Android/sdk/platform-tools/adb','-s','emulator-5560','exec-out','run-as','com.click.metaexperiment.publicapps'];folder='files/goal-first-native-navigation-'+block['output_suffix']
def remote(*args):return subprocess.check_output(adb+list(args))
data=remote('cat',folder+'/manifest.json');assert json.loads(data)==json.loads((out/'manifest.json').read_bytes());(out/'manifest-observed.json').write_bytes(data)
data=remote('cat',folder+'/episodes.jsonl');rows=[json.loads(x) for x in data.splitlines() if x.strip()];(out/'episodes.jsonl').write_bytes(data)
assert len(rows)==6 and [r['episode_id'] for r in rows]==block['episode_ids']
assert all(r['arm']=='A' and r['condition']=='normal' and r['coordinator_policy']=='current' for r in rows)
terminal=Path('/private/tmp/click-integrated-terminal-'+a.block+'.jsonl').read_bytes();[json.loads(x) for x in terminal.splitlines() if x.strip()];(out/'provider-terminal.jsonl').write_bytes(terminal)
refs={r['task_ref'] for r in rows};counts={}
for journal,alias in [('app-task-diagnostics','client-receipts'),('app-task-completion-diagnostics','completion-events')]:
 names=remote('ls','-1','no_backup/'+journal).decode().splitlines();assert set(names)<={'current.jsonl','previous.jsonl'};all_data=b'';all_rows=[]
 for name in ['previous.jsonl','current.jsonl']:
  if name not in names:continue
  data=remote('cat','no_backup/'+journal+'/'+name);rr=[json.loads(x) for x in data.splitlines() if x.strip()];(out/(journal+'-'+name)).write_bytes(data);all_rows+=rr;all_data+=data+(b'\n' if data and not data.endswith(b'\n') else b'')
 scoped=[r for r in all_rows if r.get('task_ref') in refs];(out/(alias+'-all.jsonl')).write_bytes(all_data);(out/(alias+'.jsonl')).write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in scoped));counts[journal]=dict(all=len(all_rows),scoped=len(scoped))
frozen=json.loads((base/'source-freeze.json').read_text());digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
source={p:digest(root/p) for p in frozen['source_sha256']};prepared={p:digest(base/p) for p in frozen['prepared_artifact_sha256']};assert source==frozen['source_sha256'];assert prepared==frozen['prepared_artifact_sha256']
closure=dict(closed_at_utc=datetime.now(timezone.utc).isoformat(),frozen_source_commit=frozen['frozen_source_commit'],source_files_at_close=source,prepared_artifacts_at_close=prepared,source_matches_frozen=True,prepared_matches_frozen=True,drain=drain,completion_policy=block['completion_policy'],block_id=a.block,observed_episodes=6,journals=counts)
(out/'cohort-closure.json').write_text(json.dumps(closure,indent=2)+'\n');print({'block':a.block,'episodes':len(rows),'terminal':len(terminal.splitlines()),'journals':counts})
