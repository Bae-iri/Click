"""Manual native changes are goal outcomes, without fabricating approval or action receipts."""
from copy import deepcopy
import pytest
from sqlalchemy import select

from app.models.agent_session import AgentCheckpoint, AgentTurn
from app.models.lifecycle import SafetyConfirmation
from tests.test_task_outcomes import Flow, screen


def native_screen(value=3, at=1000, scale=1.1, window=42):
    result = screen(value, at, scale)
    result['active_window_id'] = window
    result['nodes'][0].update(package_name='com.android.settings', window_id=window,
        resource_id='com.android.settings:id/font_size_seekbar')
    return result


def start_manual(direction='increase'):
    flow = Flow()
    ready = flow.prepare('글자 크기 바꿔줘', direction=direction)
    initial = flow.send('start_task', preparation_version='goal-inputs.v1',
        goal_resolution_id=ready['preparation']['id'], goal=ready['goal'], source_text=flow.source_text,
        device_context={'platform': 'android', 'locale': 'ko-KR'}, accessibility_tree=native_screen())
    assert initial['task_status'] == 'WAITING_CONFIRMATION'
    with flow.app.state.session_factory() as db:
        checkpoint = db.get(AgentCheckpoint, flow.session)
        baseline = deepcopy(checkpoint.state['manual_setting_baseline'])
        assert baseline['baseline_observation_id'] == 'o-1000'
        assert checkpoint.state['pending_attempt'] is None
    return flow, baseline


def evidence(baseline, value=4., scale=1.3):
    def frame(at, window):
        return {'observation_id': f'o-{at}', 'captured_at_ms': at, 'package_name': 'com.android.settings',
            'window_id': window, 'target_node_id': 'font-slider' if window == 43 else f'font-slider-after-{window}',
            'target_resource_id': baseline['target_resource_id'], 'target_class_name': baseline['target_class_name'],
            'control_value': value, 'range_min': 0., 'range_max': 7.,
            'applied_value': scale, 'applied_source': 'android.provider.Settings.System.FONT_SCALE'}
    return {'schema_version': 'manual-setting-evidence.v1', 'guide_command_id': baseline['guide_command_id'],
        'baseline_observation_id': baseline['baseline_observation_id'], 'observations': [frame(2000,43),frame(2500,44)]}


@pytest.mark.parametrize('direction,value,scale', [('increase',4.,1.3),('decrease',2.,.9)])
def test_manual_change_while_awaiting_approval_has_verified_outcome_without_execution(direction,value,scale):
    flow, baseline = start_manual(direction)
    changed = flow.send('observe', accessibility_tree=native_screen(value,2000,scale,window=43))
    assert changed['task_status'] == 'WAITING_CONFIRMATION'
    with flow.app.state.session_factory() as db:
        checkpoint = db.get(AgentCheckpoint,flow.session)
        assert checkpoint.state['manual_setting_baseline'] == baseline
    result = flow.send('request_result',manual_setting_evidence=evidence(baseline,value,scale))
    proof = result['metadata']['task_outcome']['technical']
    assert result['task_status'] == 'WAITING_RESULT'
    assert proof['status'] == 'CHANGED'
    assert proof['baseline_setting_value'] == 1.1 and proof['setting_value'] == scale
    assert proof['evidence_source'] == 'manual_native_observation'
    assert proof['execution_attribution'] == 'observed_only'
    assert not set(proof) & {'attempt_id','event_key','action_at_ms'}
    assert result['metadata']['task_outcome']['manual_setting_verification']['status'] == 'verified'
    assert '확인했어요' in result['metadata']['result_prompt']['message']
    with flow.app.state.session_factory() as db:
        assert db.get(SafetyConfirmation,flow.session) is None
        checkpoint=db.get(AgentCheckpoint,flow.session)
        assert checkpoint.state['manual_setting_baseline'] is None
        assert checkpoint.state['pending_attempt'] is None
    done = flow.answer('satisfied')
    assert done['metadata']['task_outcome']['final'] == 'ACHIEVED'


@pytest.mark.parametrize('case', ['guide','baseline','resource','class','range','source','unstable_control',
    'unstable_applied','opposite_control','opposite_applied','unchanged_control','unchanged_applied',
    'same_id','baseline_id','old_frame','short_settle','long_gap','noninteger','unobserved','payload_mismatch','expired'])
def test_bad_or_unsettled_manual_evidence_cannot_claim_success(case):
    flow, baseline = start_manual()
    control = 2. if case=='opposite_control' else 3. if case=='unchanged_control' else 4.
    applied = .9 if case=='opposite_applied' else 1.1 if case=='unchanged_applied' else 1.3
    flow.send('observe',accessibility_tree=native_screen(control,2000,applied,window=43))
    item=evidence(baseline,control,applied); first,last=item['observations']
    if case=='guide': item['guide_command_id']='another-guide'
    elif case=='baseline': item['baseline_observation_id']='another-observation'
    elif case=='resource': last['target_resource_id']='com.android.settings:id/brightness'
    elif case=='class': last['target_class_name']='android.widget.TextView'
    elif case=='range': first['range_max']=last['range_max']=8.
    elif case=='source': last['applied_source']='android.provider.Settings.System.SCREEN_BRIGHTNESS'
    elif case=='unstable_control': last['control_value']=4.5
    elif case=='unstable_applied': last['applied_value']=1.4
    elif case=='opposite_control': first['control_value']=last['control_value']=2.
    elif case=='opposite_applied': first['applied_value']=last['applied_value']=.9
    elif case=='unchanged_control': first['control_value']=last['control_value']=3.
    elif case=='unchanged_applied': first['applied_value']=last['applied_value']=1.1
    elif case=='same_id': last['observation_id']=first['observation_id']
    elif case=='baseline_id': first['observation_id']='o-1000'
    elif case=='old_frame': first['captured_at_ms']=900
    elif case=='short_settle': last['captured_at_ms']=2100
    elif case=='long_gap': last['captured_at_ms']=13000
    elif case=='noninteger': first['applied_value']=last['applied_value']=99.
    elif case=='unobserved': first['observation_id']='not-sent-to-server'
    elif case=='payload_mismatch': first['applied_value']=last['applied_value']=1.4
    elif case=='expired':
        now=flow.app.state.turn_service.repository.clock()
        flow.app.state.turn_service.repository.clock=lambda:now+31
    result=flow.send('request_result',manual_setting_evidence=item)
    assert result['metadata']['task_outcome']['technical']['status']=='UNVERIFIABLE'
    assert result['metadata']['task_outcome']['manual_setting_verification']['status']=='rejected'
    done=flow.answer('satisfied')
    assert done['metadata']['task_outcome']['final']=='USER_REPORTED_RESOLVED'


def test_manual_result_is_versioned_idempotent_and_cannot_be_replayed_in_another_episode():
    flow,baseline=start_manual()
    flow.send('observe',accessibility_tree=native_screen(4,2000,1.3,window=43))
    body=flow.payload('request_result',manual_setting_evidence=evidence(baseline))
    first=flow.client.post('/api/v1/agent/commands',json=body)
    assert first.status_code==200,first.text
    assert flow.client.post('/api/v1/agent/commands',json=body).json()==first.json()
    flow.body=first.json()
    body=flow.payload('request_result',manual_setting_evidence=evidence(baseline));body['episode_id']='another-episode'
    response=flow.client.post('/api/v1/agent/commands',json=body)
    assert response.status_code==409 and response.headers['x-conflict-code']=='EPISODE_MISMATCH'
    with flow.app.state.session_factory() as db:
        rows=db.scalars(select(AgentTurn).where(AgentTurn.session_id==flow.session)).all()
        assert len([row for row in rows if row.response and row.response.get('_outcome')])==1


@pytest.mark.parametrize('case', ['extra_text','fake_action','wrong_command','wrong_package','nan','bool'])
def test_schema_accepts_only_bounded_manual_observations(case):
    flow,baseline=start_manual();item=evidence(baseline)
    body=flow.payload('request_result',manual_setting_evidence=item)
    if case=='extra_text': item['observations'][0]['text']='do not export private text'
    elif case=='fake_action': body['user_action_event']=flow.event()
    elif case=='wrong_command': body['command_type']='pause_task'
    elif case=='wrong_package': item['observations'][0]['package_name']='com.example.other'
    elif case=='nan': item['observations'][0]['control_value']='NaN'
    elif case=='bool': item['observations'][0]['applied_value']=True
    assert flow.client.post('/api/v1/agent/commands',json=body).status_code==422


def test_previous_checkpoint_schema_can_resume_and_migrate_without_inventing_a_baseline():
    flow,baseline=start_manual()
    with flow.app.state.session_factory() as db:
        checkpoint=db.get(AgentCheckpoint,flow.session)
        checkpoint.schema_version=2
        state=dict(checkpoint.state);state.pop('manual_setting_baseline')
        checkpoint.state=state;db.commit()
    result=flow.send('observe',accessibility_tree=native_screen(3,1500,1.1))
    assert result['task_status']=='WAITING_CONFIRMATION'
    with flow.app.state.session_factory() as db:
        checkpoint=db.get(AgentCheckpoint,flow.session)
        assert checkpoint.schema_version==3
        assert checkpoint.state['manual_setting_baseline']['baseline_observation_id']=='o-1500'
        assert checkpoint.state['pending_attempt'] is None


def test_undo_seen_by_server_invalidates_earlier_settled_change_evidence():
    flow,baseline=start_manual()
    flow.send('observe',accessibility_tree=native_screen(4,2000,1.3,window=43))
    earlier=evidence(baseline)
    flow.send('observe',accessibility_tree=native_screen(3,3000,1.1,window=45))
    result=flow.send('request_result',manual_setting_evidence=earlier)
    assert result['metadata']['task_outcome']['technical']['status']=='UNVERIFIABLE'
    assert result['metadata']['task_outcome']['manual_setting_verification']['reason_code']=='observation_not_received'


def test_manual_observations_cannot_be_submitted_for_another_account():
    from tests.helpers import register
    flow,baseline=start_manual()
    flow.send('observe',accessibility_tree=native_screen(4,2000,1.3,window=43))
    body=flow.payload('request_result',manual_setting_evidence=evidence(baseline))
    register(flow.client)
    assert flow.client.post('/api/v1/agent/commands',json=body).status_code==403


@pytest.mark.parametrize('missing',['source','range','window'])
def test_server_requires_a_native_applied_value_and_control_before_recording_baseline(missing):
    flow=Flow();ready=flow.prepare('글자 크기 바꿔줘')
    tree=native_screen()
    if missing=='source':tree.pop('applied_settings')
    elif missing=='range':tree['nodes'][0]['extras'].pop('min');tree['nodes'][0]['extras'].pop('max')
    else:tree.pop('active_window_id');tree['nodes'][0].pop('window_id')
    flow.send('start_task',preparation_version='goal-inputs.v1',goal_resolution_id=ready['preparation']['id'],
        goal=ready['goal'],source_text=flow.source_text,device_context={'platform':'android','locale':'ko-KR'},accessibility_tree=tree)
    with flow.app.state.session_factory() as db:
        assert db.get(AgentCheckpoint,flow.session).state['manual_setting_baseline'] is None
