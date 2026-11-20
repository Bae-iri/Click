from copy import deepcopy
from app.services.settings_comparison import compare,trial_summaries


def record(run,arm,step=1,status='UNKNOWN',value=1.1):
    return dict(run_ref=run,arm=arm,at=100+step,step=step,trace_turn=step,first_observation=step==1,
        goal_type='settings.font_size.increase',scope_hash='scope',base_graph_hash='graph',
        runtime_code_hash='code',jev_model='jev-1.14.0',planner_model='gemini-3.8-flash',observation={'state_id':'S1_SETTINGS_HOME'},
        native_effect={'current_system_value':value,'current_control_value':None},
        verification={level:{'status':status if level=='final' else 'UNKNOWN'} for level in ('action','subgoal','final')},
        planner={'called':False},jev={'called':True,'provider_latency_ms':100},
        planner_calls=0,turn_latency_ms=120,total_failed_attempts=0,guide_ref='guide',command_type='highlight')


def test_matches_same_conditions_and_reports_both_success_and_availability():
    graph=record('g','graph',2,'SUCCESS')
    llm=record('l','llm',2)
    llm['planner']={'called':True,'failure_kind':'provider','status':'UNKNOWN','latency_ms':8000}
    llm['planner_calls']=1
    report=compare([record('g','graph'),graph,record('l','llm'),llm])
    group=report['groups'][0]
    assert group['status']=='DESCRIPTIVE_ONLY'
    matched=group['matched_conditions'][0]
    assert matched['graph']['native_success_rate']==1
    assert matched['llm']['native_success_rate']==0
    assert matched['llm']['provider_unavailable_trials']==1
    assert matched['llm']['unknown_or_incomplete']==1 and matched['llm']['verified_failures']==0
    assert report['recommendation']=='NOT_DETERMINED'


def test_different_start_values_or_missing_prefix_cannot_be_compared():
    report=compare([record('g','graph',value=1.1),record('l','llm',value=1.3)])
    assert report['groups'][0]['status']=='NEEDS_MATCHED_TRIALS'
    old=record('old','llm')
    old.pop('scope_hash')
    report=compare([record('g','graph'),old])
    assert report['groups'][0]['missing_comparison_context']==1
    assert report['groups'][0]['matched_conditions']==[]


def test_polling_does_not_inflate_attempt_denominators_and_completion_is_latched():
    first=record('g','graph')
    first['attempt_ref']='attempt'
    second=record('g','graph',2,'SUCCESS')
    second.update(attempt_ref='attempt')
    for level in ('action','subgoal'):second['verification'][level]['status']='SUCCESS'
    last=record('g','graph',3)
    last.update(attempt_ref='attempt',latest_user_result='ACHIEVED')
    trials=trial_summaries([first,second,last])
    assert trials[0]['action_attempts']==1 and trials[0]['action_successes']==1
    assert trials[0]['subgoal_successes']==1 and trials[0]['native_success']
    assert trials[0]['guidance_count']==1 and trials[0]['completion_observed_ms']==1000


def test_plan_invalidity_is_separate_from_provider_outage():
    item=record('l','llm')
    item['planner']={'called':True,'failure_kind':'plan'}
    item['jev']={'called':False,'status':'provider_error'}
    trial=trial_summaries([item])[0]
    assert trial['invalid_plan_attempts']==1 and not trial['provider_unavailable']


def test_model_changes_and_unknown_model_are_not_matched():
    graph, llm = record('g','graph'), record('l','llm')
    llm['planner_model']='gemini-3.5-flash'
    assert compare([graph,llm])['groups'][0]['matched_conditions']==[]
    llm.pop('planner_model')
    assert not trial_summaries([llm])[0]['complete_comparison_context']


def test_export_labels_synthetic_evidence_and_keeps_provider_errors(tmp_path):
    import json
    from app.services.settings_comparison import write_report
    graph, llm = record('g','graph',status='SUCCESS'), record('l','llm')
    llm['planner']={'called':True,'failure_kind':'provider'}
    report=compare([graph,llm])
    report['evidence_mode']='live_synthetic_replay'
    report['planner_model']='gemini-3.8-flash'
    write_report(report,tmp_path)
    saved=json.loads((tmp_path/'comparison.json').read_text(encoding='utf-8'))
    matched=saved['groups'][0]['matched_conditions'][0]
    assert matched['llm']['provider_unavailable_trials']==1
    assert matched['provider_available_only']['llm']['trials']==0
    assert '모의 데이터' in (tmp_path/'comparison.md').read_text(encoding='utf-8')
    assert '`gemini-3.8-flash`' in (tmp_path/'comparison.md').read_text(encoding='utf-8')
