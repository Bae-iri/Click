"""The exact same task success cases also run on Android."""
import json
from pathlib import Path
import pytest
from ai_agent.runtime.task_success import technical_success, normalize_technical, contracts
from app.services.task_outcome import make_outcome, answer_outcome

CASES=json.loads((Path(__file__).resolve().parents[2]/'AI-Agent/tests/fixtures/task_success_cases.json').read_text())

@pytest.mark.parametrize('case',CASES,ids=lambda c:c['name'])
def test_shared_success_contract(case):
    assert technical_success(case['technical'],case['goal_type']) is case['verified']
    outcome=make_outcome(case['technical'],100,goal_type=case['goal_type'],setting=case['technical'].get('setting'))
    final=answer_outcome(outcome,'satisfied',101)
    assert final['final']==('ACHIEVED' if case['verified'] else 'USER_REPORTED_RESOLVED')
    if not case['verified']:
        assert final['technical']['status']=='UNVERIFIABLE'


def test_every_approved_goal_has_explicit_success_kind():
    assert len(contracts())>=110
    for spec in contracts().values():
        assert spec['success']['kind'] in {'persisted_value','page_opened','native_checked','adapter_power','user_report','system_choice'}
        assert spec['success']['setting']


def test_bluetooth_connection_prompt_does_not_claim_adapter_means_connected():
    out=make_outcome({'status':'CHANGED','setting':'bluetooth'},100,goal_type='settings.bluetooth.connect',setting='bluetooth')
    assert '기기 연결은 별개' in out['prompt']['message']
    assert out['technical']['status']=='UNVERIFIABLE'


@pytest.mark.parametrize('goal,setting,state',[
    ('settings.bluetooth.enable','bluetooth','on'),
    ('settings.wifi.disable','wifi','off'),
    ('settings.font_size.decrease','font_size',None),
])
def test_result_context_comes_from_bound_goal_even_if_legacy_context_disagrees(goal,setting,state):
    outcome=make_outcome({},100,goal_type=goal,setting='brightness',
        previous={'setting':'volume','requested_state':'off'})
    assert outcome['setting']==setting
    assert outcome['requested_state']==state
