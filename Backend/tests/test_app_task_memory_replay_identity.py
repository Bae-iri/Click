"""Real Agent2 replay must not create competing copies of one grounded action."""
from copy import deepcopy
from uuid import uuid4

import pytest

from ai_agent.graph_proposal.workflows.app_tasks import analyze_app_task
from app.schemas.app_task import AppTaskRequest
from app.schemas.app_task_learning import TaskEnvironment, TaskExperience, TaskMemoryResponse
from app.services.app_task_experience import AppTaskExperienceService
from app.services.app_task_learning_policy import POLICY_HASH, digest, screen_key, target_key


def request():
    goal='검색 화면 열어줘'
    return AppTaskRequest(request_id=str(uuid4()),goal=goal,
        state={'task_id':str(uuid4()),'subgoals':[{'id':'g0','title':goal,'status':'pending'}]},
        environment={'app_version':'12','android_api':33},
        apps=[{'package':'org.example.reader','label':'Reader'}],
        screen={'status':'public','package':'org.example.reader','observation_id':str(uuid4()),
            'captured_at_ms':1000,'fingerprint':'a'*64,'width':1080,'height':2400,
            'nodes':[{'id':'node_0_1','label':'검색','resource':'org.example.reader:id/search',
                      'role':'button','clickable':True,'bounds':[10,20,400,120]}]})


def memory(req, *, kind='target_visible', after_key=None, tool='tap', at=1000,
           advances=True, outcome='verified', task_id=None, received_at=None):
    task_id=task_id or str(uuid4())
    action={'tool':tool,'target_key':target_key(req.screen.nodes[0]) if tool=='tap' else digest('none'),
        'role':'button' if tool=='tap' else 'none','expectation':kind,
        'expected_label_key':digest('검색어 입력') if kind=='target_visible' else '',
        'expected_package':'org.example.reader' if kind=='package_opened' else '',
        'advances_subgoal':advances}
    after_key=after_key or digest('observed destination')
    base={'protocol_version':'app-task-experience.v1','task_id':task_id,'goal_key':digest(req.goal),
        'subgoal_key':digest(req.goal),'environment':{'package':req.screen.package,**req.environment},
        'screen_key':screen_key(req.screen),'after_key':after_key,'after_package':req.screen.package,
        'observed_at_ms':at,'action_observed':outcome!='rejected','condition_met':outcome=='verified'}
    attempt={**base,'event_id':str(uuid4()),'sequence':1,'kind':'attempt','starts_task':True,
             'action':action,'outcome':outcome}
    terminal={**base,'event_id':str(uuid4()),'sequence':2,'kind':'result','starts_task':False,
              'action':None,'screen_key':after_key,'outcome':'user_confirmed' if outcome=='verified' else 'stopped',
              'action_observed':False,'condition_met':False,'observed_at_ms':at+1}
    records=[]
    for event in (attempt,terminal):
        event=TaskExperience.model_validate(event).model_dump(mode='json')
        records.append({'event_id':event['event_id'],'evidence':event,
                        'received_at_ms':received_at if received_at is not None else event['observed_at_ms']+10})
    analysis=analyze_app_task(records,POLICY_HASH)
    assert analysis['status']=='ANALYZED'
    if outcome=='verified':
        assert analysis['route'] and analysis['user_confirmed']
    return task_id,analysis


def recall(req,memories):
    result=TaskMemoryResponse(request_id=req.request_id,observation_id=req.screen.observation_id,
                              fingerprint=req.screen.fingerprint)
    environment=TaskEnvironment(package=req.screen.package,**req.environment)
    return AppTaskExperienceService._ground(result,req,environment,memories)


def test_repeated_real_analyzer_replay_preserves_one_native_route():
    req=request()
    memories=[memory(req,at=1000),memory(req,at=2000)]
    destination=digest('observed destination')
    for replay in range(7):
        result=recall(req,memories)
        assert result.status=='remembered',f'reuse disabled after {replay} successful replays'
        action=result.action
        assert action is not None and action.target_id=='node_0_1'
        assert action.bounds==(10,20,400,120) and action.package==req.screen.package
        assert action.expectation.kind=='screen_changed' and action.expectation.screen_key==destination
        assert result.requires_confirmation is True
        # Generic screen changes never prove native subgoal progress. Replayed
        # records therefore differ from the original target_visible advance flag.
        if replay>=2:
            assert action.advances_subgoal is False
        memories.append(memory(req,kind=action.expectation.kind,after_key=action.expectation.screen_key,
                               advances=False,at=3000+replay*1000))


def test_alias_groups_cannot_pool_one_support_each():
    req=request()
    result=recall(req,[memory(req),memory(req,kind='screen_changed',at=2000)])
    assert result.status=='needs_data' and result.action is None
    assert [e['supporting_tasks_after_adverse'] for e in result.evidence]==[1,1]


@pytest.mark.parametrize('other_kind,other_after',[
    ('screen_changed',digest('different observed destination')),
    ('selection_changed',digest('observed destination')),
])
def test_genuine_destination_or_expectation_disagreement_remains_ambiguous(other_kind,other_after):
    req=request()
    memories=[memory(req,at=1000),memory(req,at=2000),
              memory(req,kind=other_kind,after_key=other_after,at=3000),
              memory(req,kind=other_kind,after_key=other_after,at=4000)]
    result=recall(req,memories)
    assert result.status=='needs_data' and result.action is None
    assert len(result.evidence)==2 and all(e['supporting_tasks_after_adverse']==2 for e in result.evidence)


def test_package_opened_does_not_erase_different_original_destination_keys():
    req=request()
    memories=[memory(req,kind='package_opened',tool='open_app',after_key=digest(route),at=at)
              for route,at in [('route a',1000),('route a',2000),('route b',3000),('route b',4000)]]
    result=recall(req,memories)
    assert result.status=='needs_data' and result.action is None
    assert len(result.evidence)==2


def test_adverse_evidence_invalidates_both_aliases_and_needs_two_fresh_tasks():
    req=request()
    memories=[memory(req,at=1000),memory(req,at=2000),
              memory(req,kind='screen_changed',at=3000,advances=False),
              memory(req,kind='screen_changed',at=4000,advances=False)]
    assert recall(req,memories).status=='remembered'
    memories.append(memory(req,kind='screen_changed',outcome='rejected',at=5000))
    assert recall(req,memories).action is None
    # An old success arriving later cannot clear a newer observed failure.
    memories.append(memory(req,at=4500,received_at=6000))
    assert recall(req,memories).action is None
    memories.append(memory(req,at=7000))
    assert recall(req,memories).action is None
    memories.append(memory(req,at=8000))
    assert recall(req,memories).status=='remembered'


def test_duplicate_task_does_not_create_independent_alias_support():
    req=request();first=memory(req,at=1000);second=memory(req,kind='screen_changed',at=2000)
    result=recall(req,[first,deepcopy(first),second,deepcopy(second)])
    assert result.status=='needs_data' and result.action is None


def test_equivalent_qualified_actions_merge_progress_by_conservative_and():
    req=request()
    memories=[memory(req,at=1000),memory(req,at=2000),
              memory(req,kind='screen_changed',at=3000,advances=False),
              memory(req,kind='screen_changed',at=4000,advances=False)]
    for order in (memories,list(reversed(memories))):
        result=recall(req,order)
        assert result.status=='remembered' and result.action.advances_subgoal is False
