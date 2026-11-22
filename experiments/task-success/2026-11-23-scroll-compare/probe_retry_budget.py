"""Offline policy probe: unrelated applied values must not refund same-evidence retries."""
import argparse,json,os,sys
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--tree',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 sys.path[:0]=[str(a.tree/'Backend'),str(a.tree/'AI-Agent/src')]
 os.environ.update(TYPESAFE_API_KEY='test-only-key',JEV_MODEL='jev-1.14.0',JEV_MIN_CONFIDENCE='0.8',JEV_SCREEN_JUDGMENT_MODE='atomic')
 from tests.test_primary_recovery import Scenario
 from ai_agent.runtime import ClickAgentEngine
 from ai_agent.runtime.types import AgentTurnRequest,DeviceContext
 from app.clients.checkpoint import capture,restore
 rows=[]
 for drift in (False,True):
  s=Scenario([(.72,.81)]);events=[]
  for i in range(8):
   session=s.engine.session_store.get('s')
   request=AgentTurnRequest(request_id=f'r{i}',session_id='s',raw_goal=None,typed_goal=s.goal if session is None else None,
    accessibility_tree=s.nodes,device_context=DeviceContext('android'),screen_bounds=(0,0,1080,2400),active_window_id=7,
    observation_id=f'o{i}',captured_at_ms=(i+1)*1000,
    applied_settings={'screen_brightness':50+i if drift else 50,'brightness_source':'android.provider.Settings.System.SCREEN_BRIGHTNESS'})
   result=s.engine.handle_turn(request);info=result.debug.get('ui_selection',{})
   events.append({'turn':i,'calls':len(s.calls),'reason':(info.get('failure')or{}).get('reason_code'),'hold_retry':info.get('hold_retry',False),'target_selected':result.guide_command.target_node_id is not None})
   state=capture(s.engine.session_store,'s',s.graph)
   s.engine=ClickAgentEngine(session_store=restore(state),task_graphs={s.goal.task_id:s.graph},screen_resolver=s.resolver)
  rows.append({'unrelated_brightness_drift':drift,'provider_calls':len(s.calls),'turns':events})
 a.output.write_text(json.dumps({'source':'controlled_provider_no_real_AI','rows':rows},indent=2)+'\n');print(json.dumps(rows))
if __name__=='__main__':main()
