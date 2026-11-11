from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
from dataclasses import replace
from collections import Counter
import hashlib,json,shutil
import os
os.environ["DATABASE_URL"]="sqlite+pysqlite:///:memory:"
os.environ["AI1_UI_SELECTOR_PROVIDER"]="rules"
os.environ["GOAL_CLASSIFIER_PROVIDER"]="rules"
os.environ["CLICK_VALIDATION_ENVIRONMENT_ID"]=""
from app.main import create_app
from app.schemas.agent_turn import TypedGoalRequest
from app.repositories.task_graphs import canonical_graph
from app.task_graphs import releases
import app.core.validation as validation

root=Path.cwd().parent
result={}
app=create_app('sqlite+pysqlite:///:memory:')
repo=app.state.turn_service.graphs
release,graph=next((r,g) for r,g in releases() if g.definition.goal_type=='settings.bluetooth.connect' and r['approved'])
goal=TypedGoalRequest(catalog_version='2.3.0',goal_type=graph.definition.goal_type,target_app=graph.definition.target_app,slots=graph.definition.required_slots)
_,oldref=repo.select(goal)
modified=replace(graph,transitions=tuple(replace(t,guide_message=t.guide_message+' [AUDIT-CHANGE]') if t.transition_id=='MANUAL_HANDOFF' else t for t in graph.transitions))
_,newhash=canonical_graph(modified)
with patch('app.repositories.task_graphs.releases',return_value=iter([(release,modified)])):
 repo.seed_mvp()
_,afterref=repo.select(goal)
result['seed_same_version_change']={'version':release['version'],'body_changed':newhash!=oldref['sha256'],'stored_hash_unchanged':afterref['sha256']==oldref['sha256'],'startup_rejected_drift':False,'new_body_selected':afterref['sha256']==newhash}
try:repo.register(json.loads(json.dumps(modified.to_dict())),version=release['version'],approved=True,priority=release['priority'])
except Exception as e: result['seed_same_version_change']['explicit_registration_correctly_rejects']=str(e)
app.state.engine.dispose()

with TemporaryDirectory(prefix='click-audit-manifest-',dir='/private/tmp') as temp:
 temp=Path(temp)
 for part in ['Backend/app','Backend/migrations','AI-Agent/src','AI-Agent/goal_catalog']:
  shutil.copytree(root/part,temp/part,ignore=shutil.ignore_patterns('__pycache__'))
 with patch.object(validation,'ROOT',temp),patch.object(validation,'BACKEND',temp/'Backend'):
  before=validation.source_digest()
  p=temp/'AI-Agent/goal_catalog/settings_execution.json';raw=p.read_bytes();data=json.loads(raw)
  data['goals']['settings.bluetooth.connect']['instruction']='[AUDIT-CHANGED-INSTRUCTION]'
  p.write_text(json.dumps(data,ensure_ascii=False))
  after=validation.source_digest()
  result['release_manifest_omits_execution_contract']={'source_digest_unchanged':before==after,'execution_contract_file_changed':p.read_bytes()!=raw,'policy_and_vocabulary_files_unchanged':True}

front=(root/'Frontend/app/src/main/java/com/click/service/overlay/ObservationRecovery.java').read_text()
block=front.split('private static boolean navigationMenu(String stateId) {',1)[1].split('\n    }',1)[0]
import re
supported=set(re.findall(r'"([A-Z0-9_]+)"\.equals\(stateId\)',block))
missing={}
for release,graph in releases():
 if not release['approved']:continue
 absent=sorted({t.from_state for t in graph.transitions if t.allow_scroll}-supported)
 if absent:missing[graph.definition.goal_type]=absent
from tests.test_settings_execution import start
from tests.test_visible_settings_navigation import screen
_,body=start('settings.bluetooth.enable',screen('연결',[]))
result['expanded_category_empty_target_response']={'state_id':body['state_id'],'action_type':body['guide_command']['action_type'],'render_policy':body['guide_command']['render_policy']}

result['frontend_scroll_state_contract_gap']={'frontend_navigation_states':sorted(supported),'affected_goals':len(missing),'missing_state_counts':dict(Counter(s for states in missing.values() for s in states)),'examples':dict(list(missing.items())[:7])}

# No emulator/device or screen data: a continuous stream can indefinitely defer
# the trailing-edge capture when the gap is shorter than its 300 ms quiet period.
debounce=int(re.search(r'SNAPSHOT_DEBOUNCE_MS = (\d+)L',(root/'Frontend/app/src/main/java/com/click/service/accessibility/ClickAccessibilityService.java').read_text()).group(1))
result['trailing_debounce_exposure']={'configured_ms':debounce,'hypothetical_event_gap_ms':100,'maximum_wait_enforced_in_scheduler':False,'observed_in_production':False}
Path('/private/tmp/click-system-audit-reproductions.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False,indent=2))
