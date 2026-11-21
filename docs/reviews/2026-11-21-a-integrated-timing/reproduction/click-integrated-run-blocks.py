from pathlib import Path
import argparse,subprocess,sys,json,time,urllib.request
p=argparse.ArgumentParser();p.add_argument('blocks',nargs='+');a=p.parse_args()
root=Path('/Users/kimleewon/Desktop/Project/CLICK');out=root/'docs/reviews/2026-11-21-a-integrated-timing';manifest=json.loads((out/'manifest.json').read_text())
for name in a.blocks:
 block=next(x for x in manifest['blocks'] if x['block_id']==name);directory=out/'blocks'/name
 assert not (directory/'episodes.jsonl').exists(),'Neverrerun measured block'
 print(json.dumps({'event':'block_start','block':name,'policy':block['completion_policy']}),flush=True)
 with (directory/'server.log').open('w') as log:
  server=subprocess.Popen([sys.executable,'/private/tmp/click-integrated-serve.py','--policy',block['completion_policy'],'--block',name],cwd=root,stdout=log,stderr=subprocess.STDOUT)
  try:
   for _ in range(100):
    if server.poll() is not None:raise RuntimeError('Serverfailed:'+name)
    try:
     with urllib.request.urlopen('http://127.0.0.1:8769/health',timeout=.3) as r:health=json.loads(r.read())
     if health['status']=='ready':break
    except OSError:time.sleep(.1)
   else:raise RuntimeError('Readiness timeout')
   (directory/'health.json').write_text(json.dumps(health,indent=2)+'\n')
   subprocess.run([sys.executable,'/private/tmp/click-integrated-instrument.py','--policy',block['completion_policy'],'--repeat',block['repeat'],'--log',str(directory/'instrumentation.log')],check=True,cwd=root)
   subprocess.run([sys.executable,'/private/tmp/click-integrated-close.py','--block',name],check=True,cwd=root)
   rows=[json.loads(line) for line in (directory/'episodes.jsonl').read_text().splitlines() if line.strip()]
   assert not any(r.get('false_verified_offers') or r.get('guard_violations') or r.get('unexpected_state_changes') for r in rows),'Safety STOP'
   print(json.dumps({'event':'block_closed','block':name,'episodes':len(rows),'planner_requests':sum(r['planner_requests'] for r in rows)}),flush=True)
  finally:
   server.terminate()
   try:server.wait(timeout=5)
   except subprocess.TimeoutExpired:server.kill();server.wait()
 print(json.dumps({'event':'server_stopped','block':name,'owned_pid':server.pid}),flush=True)
