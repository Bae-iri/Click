from pathlib import Path
import json,sys,subprocess
root=Path('/Users/kimleewon/Desktop/Project/CLICK');out=root/'docs/reviews/2026-11-21-a-integrated-timing';manifest=json.loads((out/'manifest.json').read_text());selected=manifest['blocks'][:4]
if '--extended' in sys.argv:selected=manifest['blocks']
for name in ('episodes.jsonl','provider-terminal.jsonl'):
 data=b''
 for b in selected:
  p=out/'blocks'/b['block_id']/name
  if not p.exists():break
  raw=p.read_bytes();[json.loads(x) for x in raw.splitlines() if x.strip()];data+=raw+(b'\n' if raw and not raw.endswith(b'\n') else b'')
 (out/name).write_bytes(data)
used=out/'analysis-manifest.json' if '--extended' in sys.argv else out/'manifest.json'
subprocess.run([sys.executable,str(root/'Backend/scripts/audit_meta_controller_public_apps_provider.py'),'--episodes',str(out/'episodes.jsonl'),'--terminal',str(out/'provider-terminal.jsonl'),'--output',str(out/'request-audit.json')],check=True)
subprocess.run([sys.executable,str(root/'Backend/scripts/summarize_completion_provider_timing.py'),'--episodes',str(out/'episodes.jsonl'),'--manifest',str(used),'--request-audit',str(out/'request-audit.json'),'--output',str(out/'summary.json')],check=True)
r=json.loads((out/'summary.json').read_text());print(json.dumps({'policies':{k:{f:v.get(f) for f in ('primary_successes','mean_budget_penalized_ms','audited_model_calls_lower_bound')} for k,v in r['policies'].items()},'gate':r['comparison_gate'],'issues':r['measurement_issues'],'extension':r['conditional_extension_eligible']},indent=2))
