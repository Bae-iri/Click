"""App-independent Agent2 analysis uses device evidence, not model-generated truth."""
from copy import deepcopy
import pytest
from ai_agent.graph_proposal.workflows.app_tasks import analyze_app_task


def records():
    base={'task_id':'task','goal_key':'goal','subgoal_key':'stage','environment':{'package':'org.example.reader','app_version':'12','android_api':33},
          'protocol_version':'app-task-experience.v1','action_observed':True,'condition_met':True,'starts_task':True,
          'screen_key':'a','after_key':'b','after_package':'org.example.reader','observed_at_ms':100}
    action={'tool':'tap','target_key':'target','role':'button','expectation':'screen_changed'}
    return [{'event_id':'e1','evidence':{**base,'sequence':1,'kind':'attempt','action':action,'outcome':'verified'}},
            {'event_id':'e2','evidence':{**base,'sequence':2,'kind':'result','action':None,'outcome':'user_confirmed','screen_key':'b'}}]


def test_linked_route_requires_user_confirmation_and_is_not_independent_gold():
    result=analyze_app_task(records(),'policy')
    assert len(result['steps'])==len(result['route'])==1
    assert result['independent_device_validation']=='NOT_ASSESSED' and not result['automatic_execution']
    assert analyze_app_task(records()[:1],'policy')['route']==[]


@pytest.mark.parametrize('problem',['gap','duplicate','partial_start','moved_afterward','stopped'])
def test_incomplete_or_unconfirmed_tasks_do_not_become_complete_routes(problem):
    data=records()
    if problem=='gap':data=data[1:]
    elif problem=='duplicate':data[1]['event_id']='e1'
    elif problem=='partial_start':data[0]['evidence']['starts_task']=False
    elif problem=='moved_afterward':data[1]['evidence']['screen_key']='c'
    else:data[1]['evidence']['outcome']='stopped'
    assert analyze_app_task(data,'policy')['route']==[]


def test_no_observed_change_is_unconfirmed_not_a_proven_tool_failure():
    data=records();data[0]['evidence']['after_key']='a'
    result=analyze_app_task(data,'policy')
    assert result['steps'][0]['outcome']=='unconfirmed' and result['route']==[]
    assert result['tactics'][0]['advice']=='reobserve_before_repeating'


def test_route_preserves_loops_without_counting_one_task_as_multiple_independent_examples():
    data=records();first=deepcopy(data[0]);back=deepcopy(first);third=deepcopy(first)
    back['event_id']='back';back['evidence'].update(sequence=2,screen_key='b',after_key='a');back['evidence']['action']['tool']='back'
    third['event_id']='third';third['evidence']['sequence']=3
    data[1]['evidence']['sequence']=4
    result=analyze_app_task([first,back,third,data[1]],'policy')
    assert len(result['route'])==3 and len(result['steps'])==3


def test_model_or_inconsistent_records_cannot_supply_success():
    data=records();data[0]['evidence']['action_observed']=False
    with pytest.raises(ValueError):analyze_app_task(data,'policy')
    data=records();data[1]['evidence']['goal_key']='other'
    with pytest.raises(ValueError):analyze_app_task(data,'policy')


def test_terminal_result_cannot_be_inserted_in_the_middle_of_a_trace():
    data=records();extra=deepcopy(data[0]);extra['event_id']='after';extra['evidence']['sequence']=3
    result=analyze_app_task([*data,extra],'policy')
    assert result['status']=='NEEDS_DATA' and result['steps']==[]
