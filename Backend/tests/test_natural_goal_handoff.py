"""Novel utterances go through the configured classifier into the graph input contract."""
from unittest.mock import Mock
import httpx
import pytest
from tests.test_task_outcomes import Flow
from app.services.goal_jev import JevGoalClassifier
from tests.test_goal_jev import response_for

CASES = [
    ('눈이 부시니 빛이 덜 나오게 해줘', 'settings.brightness.decrease', {'scope':'system','direction':'decrease'}),
    ('빛이 부족해. 좀 환하게 보여줘', 'settings.brightness.increase', {'scope':'system','direction':'increase'}),
    ('유튜브 재생이 귀에 거슬려. 한결 잔잔하게 들리게 해줘', 'settings.volume.decrease', {'scope':'system','app_name':'유튜브','audio_stream':'media','direction':'decrease'}),
    ('유튜브 재생이 잘 안 들려. 더 우렁차게 들려줘', 'settings.volume.increase', {'scope':'system','app_name':'유튜브','audio_stream':'media','direction':'increase'}),
    ('밤이라 검은 배경 테마로 전환해줘', 'settings.dark_mode.enable', {'state':'on'}),
    ('검은 배경 대신 흰 바탕 테마를 쓰고 싶어', 'settings.dark_mode.disable', {'state':'off'}),
]

@pytest.mark.parametrize('text,goal,values', CASES)
def test_classified_unknown_phrase_recomputes_selected_family_inputs(monkeypatch,text,goal,values):
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only-secret')
    monkeypatch.setenv('JEV_MODEL','jev-1.14.0')
    calls=[]
    def send(payload,**kwargs):
        calls.append(payload)
        criteria=payload['questions']['goal']['criteria']
        choice=next(k for k,c in criteria.items() if isinstance(c,dict) and c.get('goal_type')==goal)
        return httpx.Response(200,json=response_for(payload,choice))
    f=Flow();f.app.state.turn_service.goal_preparation.llm=JevGoalClassifier(send)
    result=f.resolve(text)
    assert result['resolution_status']=='confirmation_required', result
    assert result['goal']['goal_type']==goal
    assert result['preparation']['values']==values
    assert result['preparation']['graph_reference']
    assert len(calls)==1


def test_unmatched_request_does_not_send_conflicting_family_field_definitions():
    f=Flow();classifier=Mock()
    classifier.interpret_inputs.return_value={'goal_type':'settings.brightness.decrease','inputs':{}}
    f.app.state.turn_service.goal_preparation.llm=classifier
    result=f.resolve('눈이 부시니 빛이 덜 나오게 해줘')
    assert classifier.interpret_inputs.call_args.args[2]==[]
    assert result['goal']['goal_type']=='settings.brightness.decrease'


def test_model_cannot_reinterpret_a_keyword_bound_family():
    f=Flow();classifier=Mock()
    classifier.interpret_inputs.return_value={'goal_type':'settings.volume.increase','inputs':{'direction':'increase'}}
    f.app.state.turn_service.goal_preparation.llm=classifier
    result=f.resolve('글자 크기 키워줘')
    assert result['goal'] is None
    assert result['preparation']['family']=='font_size'
    candidates=classifier.interpret_inputs.call_args.args[1]
    assert all(c['goal_type'].startswith('settings.font_size.') for c in candidates)


def test_missing_font_scope_is_not_created_from_an_inferred_goal():
    f=Flow();classifier=Mock()
    classifier.interpret_inputs.return_value={'goal_type':'settings.font_size.increase','inputs':{'scope':'system'}}
    f.app.state.turn_service.goal_preparation.llm=classifier
    result=f.resolve('읽을 문자가 깨알처럼 보여')
    assert result['goal'] is None
    assert result['preparation']['question']['field']=='scope'
    assert 'scope' not in result['preparation']['values']


def test_explicit_app_scope_is_not_overwritten_after_model_selection():
    f=Flow();classifier=Mock()
    classifier.interpret_inputs.return_value={'goal_type':'settings.brightness.decrease','inputs':{}}
    f.app.state.turn_service.goal_preparation.llm=classifier
    result=f.resolve('유튜브 앱에서만 빛이 덜 나오게 해줘')
    assert result['goal'] is None
    assert result['preparation']['values']['scope']=='app'
    assert result['preparation']['status']=='unsupported'


@pytest.mark.parametrize('provider,configured', [('jev',True),('self_hosted',False),('private-provider-value',False)])
def test_public_goal_configuration_exposes_only_provider_and_configuration_state(monkeypatch,provider,configured):
    monkeypatch.setenv('GOAL_CLASSIFIER_PROVIDER',provider)
    monkeypatch.setenv('TYPESAFE_API_KEY','private-test-key')
    monkeypatch.delenv('SELF_HOSTED_LLM_MODEL',raising=False)
    from app.services.goal_classifier import goal_classifier_configuration
    state=goal_classifier_configuration()
    body={'goal_interpretation':state} if provider not in {'jev','self_hosted'} else Flow().client.get('/version').json()
    assert body['goal_interpretation']==state
    assert state=={'provider':provider if provider in {'jev','self_hosted'} else 'invalid','configured':configured}
    encoded=str(body)
    assert 'private-test-key' not in encoded and 'private-provider-value' not in encoded


def test_approved_deployment_uses_jev_for_goal_interpretation():
    from pathlib import Path
    import yaml
    root=Path(__file__).parents[2]
    assert 'GOAL_CLASSIFIER_PROVIDER=jev' in (root/'Backend/Dockerfile').read_text()
    service=yaml.safe_load((root/'render.yaml').read_text())['services'][0]
    env={entry['key']:entry.get('value') for entry in service['envVars']}
    assert env['GOAL_CLASSIFIER_PROVIDER']=='jev'
    assert env['AI1_UI_SELECTOR_PROVIDER']=='jev'


@pytest.mark.parametrize('run_live',[False,True])
def test_live_flow_check_requires_real_configuration_and_uses_only_disposable_data(monkeypatch,capsys,run_live):
    from scripts.check_natural_goal_flow import main, CASES
    monkeypatch.setenv('GOAL_CLASSIFIER_PROVIDER','jev')
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only-secret')
    monkeypatch.setenv('JEV_MODEL','jev-1.14.0')
    monkeypatch.setenv('DATABASE_URL','postgresql://must-not-connect.invalid/production')
    calls=[]
    expected={source:goal or ('cancel' if question=='request' else 'settings.font_size.increase')
              for _,source,goal,question in CASES}
    def send(self,payload,**kwargs):
        calls.append(payload)
        goal=expected[payload['state']]
        criteria=payload['questions']['goal']['criteria']
        choice=goal if goal=='cancel' else next(k for k,c in criteria.items() if isinstance(c,dict) and c.get('goal_type')==goal)
        return httpx.Response(200,json=response_for(payload,choice))
    monkeypatch.setattr(JevGoalClassifier,'_post',send)
    assert main(['--run-live'] if run_live else [])==0
    output=capsys.readouterr().out
    assert len(calls)==(8 if run_live else 0)
    assert 'test-only-secret' not in output and 'production' not in output
    if run_live: assert '8/8' in output


def test_unconfigured_live_check_never_requests_a_model(monkeypatch,capsys):
    from scripts.check_natural_goal_flow import main
    monkeypatch.setenv('GOAL_CLASSIFIER_PROVIDER','jev')
    monkeypatch.delenv('TYPESAFE_API_KEY',raising=False)
    monkeypatch.setattr(JevGoalClassifier,'_post',lambda *a,**k:pytest.fail('No network without configuration'))
    assert main(['--run-live'])==2
    assert 'CONFIG_ERROR' in capsys.readouterr().out



def test_self_hosted_interpreter_can_select_an_unknown_goal_without_union_field_conflicts(monkeypatch):
    from app.services.goal_llm import LocalGoalLlm
    import json
    monkeypatch.setenv('SELF_HOSTED_LLM_URL','https://inference.example/v1/chat/completions')
    monkeypatch.setenv('SELF_HOSTED_LLM_TOKEN','test-only-secret')
    monkeypatch.setenv('SELF_HOSTED_LLM_MODEL','test-model')
    calls=[]
    def send(payload,**kwargs):
        calls.append(payload)
        assert json.loads(payload['messages'][0]['content'].split('\n')[-1])['input_fields']==[]
        return httpx.Response(200,json={'choices':[{'finish_reason':'stop','message':{'content':json.dumps({
            'goal_type':'settings.brightness.decrease','inputs':{}})}}]})
    f=Flow();f.app.state.turn_service.goal_preparation.llm=LocalGoalLlm(send)
    body=f.resolve('눈이 부시니 빛이 덜 나오게 해줘')
    assert body['goal']['goal_type']=='settings.brightness.decrease'
    assert body['preparation']['values']=={'scope':'system','direction':'decrease'}
    assert len(calls)==1
