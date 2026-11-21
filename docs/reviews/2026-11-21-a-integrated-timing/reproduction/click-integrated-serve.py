import argparse,runpy,sys,os,json
from pathlib import Path
from dotenv import load_dotenv
p=argparse.ArgumentParser();p.add_argument('--policy',choices=['gemini','jev'],required=True);p.add_argument('--block',required=True);a=p.parse_args()
root=Path('/Users/kimleewon/Desktop/Project/CLICK');out=root/'docs/reviews/2026-11-21-a-integrated-timing';assert (out/'source-freeze.json').exists(),'Freeze beforelive'
load_dotenv(root/'Backend/.env');os.environ['APP_TASK_PROVIDER']='gemini'
os.environ['APP_TASK_COMPLETION_PROVIDER']=a.policy
assert os.getenv('GEMINI_SETTINGS_MODEL','gemini-3.8-flash').strip()=='gemini-3.8-flash'
assert os.getenv('JEV_MODEL','jev-1.14.0').strip()=='jev-1.14.0'
terminal=Path('/private/tmp/click-integrated-terminal-'+a.block+'.jsonl');assert not terminal.exists(),'Fresh terminal required'
sys.path[:0]=[str(root/'Backend'),str(root/'Backend/scripts'),str(root/'AI-Agent/src')]
from app.services.goal_jev import JevGoalClassifier
if a.policy=='jev':
 key,model,minimum=JevGoalClassifier.configuration();assert key and model=='jev-1.14.0';del key
print(json.dumps(dict(owned_pid=os.getpid(),completion_policy=a.policy,block_id=a.block,planner_model='gemini-3.8-flash',jev_model='jev-1.14.0')),flush=True)
sys.argv=['serve_meta_controller_public_apps_experiment.py','--allow-live','--port','8769','--manifest',str(root/'experiments/metacontroller/native-public-apps-catalogue-v1.json'),'--output',str(terminal)]
runpy.run_path(str(root/'Backend/scripts/serve_meta_controller_public_apps_experiment.py'),run_name='__main__')
