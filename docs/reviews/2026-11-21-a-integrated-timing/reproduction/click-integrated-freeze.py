from pathlib import Path
from datetime import datetime,timezone
import hashlib,json,socket,subprocess
root=Path('/Users/kimleewon/Desktop/Project/CLICK');out=root/'docs/reviews/2026-11-21-a-integrated-timing'
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def git(*args):return subprocess.check_output(['git',*args],cwd=root).decode().strip()
s=socket.socket();s.settimeout(.2);assert s.connect_ex(('127.0.0.1',8769))!=0,'Facade must be OFF';s.close()
assert not list(Path('/private/tmp').glob('click-integrated-terminal-*.jsonl')),'Fresh provider logs required'
manifest=json.loads((out/'manifest.json').read_text());assert len(manifest['assignments'])==24 and len(manifest['conditional_assignments'])==12 and manifest['extended'] is False
component=json.loads((out/'native-component.json').read_text());assert component['passed'] is True and component['model_calls']==0 and len(component['cases'])==9
old=json.loads((root/'docs/reviews/2026-11-21-goal-first-completion/source-freeze.json').read_text());paths=set(old['source_sha256'])
paths.update(p for p in git('diff','--name-only','a89168d..HEAD').splitlines() if not p.startswith('docs/'))
for p in paths:assert digest(root/p)==hashlib.sha256(subprocess.check_output(['git','show','HEAD:'+p],cwd=root)).hexdigest(),p+' is not committed'
prepared=['environment.json','manifest.json','native-component.json','unit-verification.json']+['blocks/'+x['block_id']+'/manifest.json' for x in manifest['blocks']]
r=dict(schema_version='click.a-completion-timing-source-freeze.v1',frozen_at_utc=datetime.now(timezone.utc).isoformat(),frozen_source_commit=git('rev-parse','HEAD'),frozen_source_tree=git('rev-parse','HEAD^{tree}'),models=dict(navigation='gemini-3.8-flash',gemini_completion='gemini-3.8-flash',jev_completion='jev-1.14.0'),model_attempts_before_freeze=0,live_facade_not_started_at_freeze=True,loopback_port_closed_before_freeze=True,primary_scheduled_episodes=24,conditional_scheduled_episodes=12,arm='A/CURRENT only',condition='normal',source_sha256={p:digest(root/p) for p in sorted(paths)},apk_sha256={k:digest(Path('/private/tmp/click-jev-integrated-apks')/p) for k,p in [('isolated_debug','app-debug.apk'),('instrumentation','test.apk')]},prepared_artifact_sha256={p:digest(out/p) for p in prepared})
(out/'source-freeze.json').write_text(json.dumps(r,indent=2)+'\n');print({k:r[k] for k in ['frozen_at_utc','frozen_source_commit','primary_scheduled_episodes','model_attempts_before_freeze']})
