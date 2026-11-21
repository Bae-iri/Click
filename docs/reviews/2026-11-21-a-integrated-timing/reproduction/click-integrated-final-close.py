from pathlib import Path
from datetime import datetime,timezone
import hashlib,json,socket
root=Path('/Users/kimleewon/Desktop/Project/CLICK');out=root/'docs/reviews/2026-11-21-a-integrated-timing';f=json.loads((out/'source-freeze.json').read_text());h=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
s=socket.socket();s.settimeout(.2);assert s.connect_ex(('127.0.0.1',8769))!=0;s.close()
source={p:h(root/p) for p in f['source_sha256']};prepared={p:h(out/p) for p in f['prepared_artifact_sha256']};assert source==f['source_sha256'];assert prepared==f['prepared_artifact_sha256']
manifest=json.loads((out/'manifest.json').read_text());blocks={}
for b in manifest['blocks'][:4]:
 p=out/'blocks'/b['block_id']/'cohort-closure.json';r=json.loads(p.read_text());assert r['source_matches_frozen'] and r['prepared_matches_frozen'] and r['drain']['status']=='idle';blocks[b['block_id']]=h(p)
r=dict(closed_at_utc=datetime.now(timezone.utc).isoformat(),frozen_source_commit=f['frozen_source_commit'],source_matches_frozen=True,prepared_matches_frozen=True,source_files_at_close=source,prepared_artifacts_at_close=prepared,block_closure_sha256=blocks,loopback_port_closed_at_close=True,observed_primary_episodes=24)
(out/'cohort-closure.json').write_text(json.dumps(r,indent=2)+'\n');print({'closed_at':r['closed_at_utc'],'source':len(source),'prepared':len(prepared),'blocks':len(blocks)})
