"""Read-only structured A/B traces and per-goal summaries; no provider calls."""
import argparse
from collections import defaultdict
import json
import os
from pathlib import Path
import sys
import time
import subprocess
from queue import Queue, Empty
from threading import Thread
from hashlib import sha256

BACKEND=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BACKEND))
sys.path.insert(0,str(BACKEND.parent/'AI-Agent/src'))


def preparation_record(row):
    response=row.response or {}
    prep=response.get('preparation') or {}
    status=response.get('resolution_status')
    metadata=response.get('metadata') or {}
    explicit=metadata.get('settings_experiment_preparation') or {}
    goal=(response.get('goal') or {}).get('goal_type')
    if not explicit and not (goal and goal.startswith('settings.') or prep.get('family') in {'font_size','brightness','volume'}):
        return None
    return {'event':'goal_preparation','source':'backend','arm':explicit.get('arm','unknown'),
        'run_ref':sha256(row.session_id.encode()).hexdigest()[:16],
        'at':(metadata.get('goal_preparation_diagnostic') or {}).get('observed_at',row.expires_at-600),
        'resolution_status':status,'preparation_status':row.status,'goal_type':goal,
        'missing_fields':[f for f in prep.get('missing_fields',[]) if f in {'scope','direction','audio_stream','request'}],
        'status':'WAITING' if status=='confirmation_required' else 'BLOCKED' if status=='unsupported' else 'UNKNOWN',
        'reason_code':'awaiting_start_confirmation' if status=='confirmation_required' else 'awaiting_required_inputs' if status=='needs_clarification' else 'preparation_unavailable'}


def device_logs():
    queue=Queue()
    adb=BACKEND.parents[1]/'.android-sdk/platform-tools/adb.exe'
    if not adb.is_file():return None,queue
    child_env={k:v for k,v in os.environ.items() if k not in {
        'GEMINI_API_KEY','OPENROUTER_API_KEY','TYPESAFE_API_KEY','DATA_ENCRYPTION_KEYS','DATA_LOOKUP_KEY','DATABASE_URL'}}
    process=subprocess.Popen([str(adb),'logcat','-T','1','-v','raw','-s','ClickExperiment:I','*:S'],
        stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,encoding='utf-8',errors='replace',env=child_env)
    def read():
        try:
            for line in process.stdout:
                try:
                    value=json.loads(line)
                    if value.get('event')!='device_preflight' or value.get('arm') not in {'graph','llm'}:continue
                    allowed={'event','arm','stage','status','reason_code','screen_scope','privacy_policy_loaded','node_count','at_ms'}
                    queue.put({**{k:v for k,v in value.items() if k in allowed},'source':'device'})
                except (ValueError,AttributeError):continue
        finally:process.stdout.close()
    Thread(target=read,daemon=True).start()
    return process,queue


def summarize(records):
    trials={}
    for record in records:
        key=record['run_ref']
        previous=trials.get(key)
        trials[key]={'last':record,'first_at':previous['first_at'] if previous else record['at'],
            'planner_error':bool(previous and previous['planner_error']) or bool(
                record['planner'].get('called') and record['planner'].get('status')!='SUCCESS'),
            'jev_calls':(previous['jev_calls'] if previous else 0)+int(bool(record['jev'].get('called'))),
            'planner_ms':(previous['planner_ms'] if previous else 0)+record['planner'].get('latency_ms',0)}
    groups=defaultdict(list)
    for trial in trials.values():
        groups[(trial['last']['arm'],trial['last']['goal_type'])].append(trial)
    return [{'arm':arm,'goal_type':goal,'trials':len(items),
        'native_goal_successes':sum(x['last']['verification']['final']['status']=='SUCCESS' for x in items),
        'user_confirmed_achieved':sum(x['last'].get('latest_user_result')=='ACHIEVED' for x in items),
        'final_unknown_or_incomplete':sum(x['last']['verification']['final']['status']=='UNKNOWN' for x in items),
        'final_blocked':sum(x['last']['verification']['final']['status']=='BLOCKED' for x in items),
        'planner_calls':sum(x['last']['planner_calls'] for x in items),
        'planner_unavailable_trials':sum(x['planner_error'] for x in items),
        'jev_calls':sum(x['jev_calls'] for x in items),
        'planner_latency_ms':sum(x['planner_ms'] for x in items),
        'failed_attempts':sum(x['last']['total_failed_attempts'] for x in items),
        'observed_wall_ms':sum(max(0,x['last']['at']-x['first_at'])*1000 for x in items)}
        for (arm,goal),items in sorted(groups.items())]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file',type=Path,default=BACKEND/'instance/settings-experiment/.env')
    parser.add_argument('--follow',action='store_true')
    parser.add_argument('--history',action='store_true',help='With --follow, include records from before this watcher started')
    parser.add_argument('--summary',action='store_true')
    parser.add_argument('--compare',action='store_true',help='Matched conditions and descriptive A/B metrics')
    parser.add_argument('--output-dir',type=Path,help='With --compare: save comparison.json and comparison.md')
    parser.add_argument('--arm',choices=('graph','llm'))
    args=parser.parse_args()
    watch_started = int(time.time())
    if args.compare and (args.follow or args.summary or args.arm):
        parser.error('--compare must include both arms; do not combine with --follow/--summary/--arm')
    if args.output_dir and not args.compare:parser.error('--output-dir requires --compare')
    from dotenv import load_dotenv
    if not args.env_file.is_file():parser.error('Prepare the experiment environment first')
    load_dotenv(args.env_file,override=True)
    os.chdir(BACKEND)
    from sqlalchemy import select
    from sqlalchemy.engine import make_url
    from app.database import build_engine,build_session_factory
    from app.models.runtime_event import RuntimeEvent
    from app.models.agent_session import AgentSession
    from app.models.agent_session import AgentGoalResolution
    from app.core.data_encryption import validate_database_keys
    raw=os.getenv('DATABASE_URL','')
    if not raw:parser.error('DATABASE_URL is required')
    url=make_url(raw)
    if not url.drivername.startswith('sqlite') or not url.database or not Path(url.database).is_file():
        parser.error('Use the existing isolated SQLite experiment database')
    engine=build_engine(raw)
    device=None
    try:
        validate_database_keys(engine)
        factory=build_session_factory(engine)
        seen=set()
        if args.follow and not args.summary:
            device,device_queue=device_logs()
            print(json.dumps({'event':'watching','backend':True,'device_logs':device is not None,
                'since':None if args.history else watch_started,
                'message':'새 작업 준비·실행 로그 대기 중. 이전 기록은 --history로 확인합니다.'},ensure_ascii=False),flush=True)
        while True:
            records=[]
            with factory() as db:
                if not args.summary and not args.compare:
                    for prep in db.scalars(select(AgentGoalResolution).where(AgentGoalResolution.expires_at>int(time.time()))):
                        record=preparation_record(prep)
                        identity=('preparation',prep.id,prep.status)
                        if record and identity not in seen and (not args.arm or record['arm']==args.arm) and (
                                not args.follow or args.history or record['at'] >= watch_started):
                            print(json.dumps(record,ensure_ascii=False),flush=True)
                            seen.add(identity)
                events=list(db.scalars(select(RuntimeEvent).where(RuntimeEvent.kind=='turn',
                    RuntimeEvent.expires_at>int(time.time())).order_by(RuntimeEvent.created_at,RuntimeEvent.session_id,RuntimeEvent.sequence)))
                for event in events:
                    trace=event.payload.get('settings_experiment')
                    if not trace or args.arm and trace['arm']!=args.arm:continue
                    record={'at':event.created_at,'step':event.sequence,**trace}
                    row=db.get(AgentSession,event.session_id)
                    if row and row.episode_id==event.episode_id:
                        record['latest_user_result']=(row.task_outcome or {}).get('final')
                    records.append(record)
                    if not args.summary and not args.compare and event.id not in seen and (
                            not args.follow or args.history or event.created_at >= watch_started):
                        print(json.dumps(record,ensure_ascii=False),flush=True)
                        seen.add(event.id)
            if args.summary:print(json.dumps(summarize(records),ensure_ascii=False,indent=2),flush=True)
            if args.compare:
                from app.services.settings_comparison import compare, write_report
                report=compare(records)
                report['evidence_mode']='device_runtime_logs'
                print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
                if args.output_dir:write_report(report,args.output_dir)
            if args.follow and not args.summary:
                while True:
                    try:record=device_queue.get_nowait()
                    except Empty:break
                    if not args.arm or record['arm']==args.arm:
                        print(json.dumps(record,ensure_ascii=False),flush=True)
            if not args.follow:break
            time.sleep(2)
    finally:
        if device is not None:
            device.terminate()
            try:device.wait(timeout=2)
            except subprocess.TimeoutExpired:device.kill()
        engine.dispose()


if __name__=='__main__':main()
