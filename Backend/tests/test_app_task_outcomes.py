import json
from types import SimpleNamespace
import pytest
from app.schemas.app_task import OutcomeClause, OutcomeGoal, TaskCompletion, TaskResultCheck
from app.services import app_task_outcome as outcome
from app.services.app_task import AppTaskService
from tests.test_app_task_goal_grounding import unseeded

@pytest.fixture
def compound():
    return unseeded('보관함을 열고 자동 동기화를 꺼줘', [
        dict(label='보관함', role='text', native_heading=True),
        dict(label='자동 동기화', role='toggle', checked=True, clickable=True)])

def test_goal_compilation_sees_no_screen_and_is_bound_to_task(compound):
    compound.goal = semantic_goal = '먼저 보관함에 가. 그다음 자동 동기화는 꺼진 상태로 해줘'
    clauses=[OutcomeClause(kind='page',labels=['보관함','Library']),OutcomeClause(kind='off',labels=['자동 동기화'])]
    def sender(payload, timeout):
        assert 'n1' not in payload['input'] and 'CURRENT SCREEN' not in payload['input']
        raw=json.dumps(dict(clauses=[c.model_dump() for c in clauses]))
        return SimpleNamespace(status_code=200,content=json.dumps(dict(status='completed',model='qwen/qwen3.8-flash',
            output=[dict(type='message',role='assistant',content=[dict(type='output_text',text=raw)])])).encode())
    service=AppTaskService(sender);compound.prepare_outcome=True
    compiled=service.step(compound).outcome_goal
    assert compiled and compiled.clauses==clauses
    compound.state.outcome_goal=compiled;compound.prepare_outcome=False
    assert outcome.authentic(compound)
    compound.goal='다른 요청'
    assert service.step(compound).diagnostic['reason_code']=='outcome_contract_changed'
    compound.goal=semantic_goal
    compound.state.outcome_goal=OutcomeGoal(clauses=clauses[:1],seal=compiled.seal)
    assert not outcome.authentic(compound)


@pytest.mark.parametrize(('goal', 'effects'), [
    ('시계에서 스톱워치 탭을 열어줘', [('selected', '스톱워치', 0)]),
    ('open the Library page', [('page', 'Library', 0)]),
    ('보관함을 열고 자동 동기화를 꺼줘', [('page', '보관함', 0), ('off', '자동동기화', 0)]),
    ('글자 크기를 한 단계 키워줘', [('increase', '글자 크기', 1)]),
])
def test_literal_start_is_signed_without_model_or_screen(compound, goal, effects):
    compound.goal = goal
    compound.prepare_outcome = True
    service = AppTaskService(lambda *a, **kw: pytest.fail('unnecessary model call'))
    response = service.step(compound)
    assert response.diagnostic['called'] is False
    assert response.diagnostic['model'] == 'local'
    assert outcome.authentic(compound, contract=response.outcome_goal)
    assert [(c.kind, c.labels[0], c.steps) for c in response.outcome_goal.clauses] == effects
    assert service.step(compound).outcome_goal == response.outcome_goal


@pytest.mark.parametrize('goal', ['예전에 저장한 것들을 보여줘', 'A 말고 B 탭을 열어줘',
                                 'open the Library page and turn off sync', '즐겨 보던 화면을 보여줘',
                                 'open the next tab', '그 화면을 열어줘',
                                 'Chrome에서 새 탭을 열어줘', 'open a new tab'])
def test_semantic_or_compound_navigation_is_not_frozen_as_a_literal(goal):
    assert not outcome.named_destination(goal)

def test_paraphrased_display_keeps_flash_and_omits_numeric_fields(compound):
    compound.goal = '예전에 저장한 것들을 보여줘'
    compound.prepare_outcome = True
    def sender(payload, timeout):
        assert payload['model'] == 'qwen/qwen3.8-flash'
        item = payload['text']['format']['schema']['properties']['clauses']['items']
        assert set(item['required']) == {'kind', 'labels'}
        assert 'n1' not in payload['input']
        answer = json.dumps({'clauses': [{'kind':'page', 'labels':['보관함', 'Library']}]})
        body = {'status':'completed', 'model':payload['model'],
                'output':[{'type':'message','role':'assistant','content':[{'type':'output_text','text':answer}]}]}
        return SimpleNamespace(status_code=200, content=json.dumps(body).encode())
    result = AppTaskService(sender).step(compound)
    assert result.diagnostic['called'] and outcome.authentic(compound, contract=result.outcome_goal)
    # The user's own words are sealed with the model's aliases, as they are matched on screen.
    assert result.outcome_goal.clauses == [OutcomeClause(kind='page', labels=['보관함', 'Library', '저장한 것들'])]


# Production device audit 2026-11-23: each tab was reached, but these model clauses were sealed as-is
# and never matched the on-screen tab text, so 5 of 7 arrivals were never confirmed.
@pytest.mark.parametrize(('goal', 'app', 'raw', 'sealed'), [
    ('시계에서 스톱워치 탭으로 이동해줘', '시계', [('selected', ['스톱워치 탭'])], [('selected', ['스톱워치'])]),
    ('시계에서 타이머 탭으로 이동해줘', '시계', [('page', ['Clock'])], [('page', ['Clock', '타이머'])]),
    ('시계에서 알람 탭으로 이동해줘', '시계', [('page', ['Clock']), ('selected', ['알람', 'Alarm'])],
     [('selected', ['알람', 'Alarm'])]),
    ('YouTube에서 구독 탭으로 이동해줘', 'YouTube', [('page', ['YouTube 구독 탭', 'YouTube Subscriptions Tab'])],
     [('page', ['구독', 'Subscriptions'])]),
    # The app's own name never becomes a destination, even when the user said it.
    ('Chrome 설정 화면을 열어줘', 'Chrome', [('page', ['Chrome 설정'])], [('page', ['설정'])]),
])
def test_model_labels_are_sealed_as_they_appear_on_screen(compound, goal, app, raw, sealed):
    compound.goal = goal
    compound.apps = [compound.apps[0].model_copy(update={'label': app})] if compound.apps else compound.apps
    compound.prepare_outcome = True
    def sender(payload, timeout):
        answer = json.dumps({'clauses': [dict(kind=k, labels=l, metric='none', steps=0) for k, l in raw]})
        body = {'status':'completed', 'model':payload['model'],
                'output':[{'type':'message','role':'assistant','content':[{'type':'output_text','text':answer}]}]}
        return SimpleNamespace(status_code=200, content=json.dumps(body).encode())
    result = AppTaskService(sender).step(compound)
    assert result.diagnostic['called'] and outcome.authentic(compound, contract=result.outcome_goal)
    assert [(c.kind, c.labels) for c in result.outcome_goal.clauses] == sealed


def test_only_full_correct_native_proof_survives(compound):
    clauses=outcome.legacy_compound(compound.goal)
    title=TaskResultCheck(package=compound.screen.package,label='보관함',role='text')
    wrong=TaskResultCheck(package=compound.screen.package,label='자동 동기화',role='toggle',checked=True)
    assert not outcome.covers(clauses,TaskCompletion(checks=[title]),compound.screen)
    assert not outcome.covers(clauses,TaskCompletion(checks=[title,wrong]),compound.screen)
    compound.screen.nodes[1].checked=False
    assert outcome.covers(clauses,TaskCompletion(checks=[title,wrong.model_copy(update={'checked':False})]),compound.screen)

def test_missing_target_does_not_allow_an_unrelated_already_off_switch(compound):
    clauses=outcome.legacy_compound(compound.goal)
    goal=OutcomeGoal(clauses=clauses,seal='0'*64)
    facts={'c0':('c0','visible',None),'c1.checked_true':('c1','checked',True),'c2.checked_false':('c2','checked',False)}
    controls={'c0':{'role':'text','label':'보관함'},'c1':{'role':'toggle','label':'자동 동기화'},'c2':{'role':'toggle','label':'진동'}}
    assert outcome.binding_choices(goal,facts,controls)=={'r0':['none','c0'],'r1':['none','c1.checked_true']}


def test_literal_requirements_survive_an_inverted_or_incomplete_model_answer():
    anchors = outcome.explicit_outcomes('보관함을 열고 자동 동기화를 꺼줘')
    wrong = [OutcomeClause(kind='on', labels=['자동 동기화', 'auto sync'])]
    clauses = outcome.reconcile(wrong, anchors)
    assert [c.kind for c in clauses] == ['page', 'off']
    assert clauses[1].labels == ['자동 동기화', 'auto sync']
    requested = outcome.explicit_outcomes('글자 크기를 두 단계 줄여줘')
    numeric = outcome.reconcile([OutcomeClause(kind='increase', labels=['글자 크기'], metric='font_size', steps=1)], requested)
    assert (numeric[0].kind, numeric[0].metric, numeric[0].steps) == ('decrease','font_size',2)


def test_confirmation_checks_current_native_value_even_with_correct_predicted_selector(compound):
    clauses=outcome.legacy_compound(compound.goal)
    proof=TaskCompletion(checks=[TaskResultCheck(package=compound.screen.package,label='보관함',role='text'),
        TaskResultCheck(package=compound.screen.package,label='자동 동기화',role='toggle',checked=False)])
    assert outcome.covers(clauses,proof,compound.screen)
    assert not outcome.covers(clauses,proof,compound.screen,observed=True)


def test_stored_contract_survives_retries_workers_and_stripping(compound,tmp_path):
    from fastapi.testclient import TestClient
    from app.main import create_app
    from tests.helpers import register
    app=create_app('sqlite+pysqlite:///'+str(tmp_path/'outcomes.db'))
    with TestClient(app) as client:
        user=register(client)['user_id']
        store=outcome.OutcomeStore(app.state.session_factory)
        clauses=outcome.legacy_compound(compound.goal)
        signed=OutcomeGoal(clauses=clauses,seal=outcome._seal(compound,clauses,user))
        assert store.contract(user,compound.state.task_id,signed)==signed
        store2=outcome.OutcomeStore(app.state.session_factory)
        changed=OutcomeGoal(clauses=clauses[:1],seal=outcome._seal(compound,clauses[:1],user))
        assert store2.contract(user,compound.state.task_id,changed)==signed
        service=AppTaskService(lambda *args,**kwargs:pytest.fail('must use the stored contract'),outcome_store=store2)
        compound.prepare_outcome=True
        assert service.step(compound,user).outcome_goal==signed
        compound.state.outcome_goal=changed
        assert service.step(compound,user).diagnostic['reason_code']=='outcome_contract_changed'
        compound.state.outcome_goal=None
        compound.goal='진동을 켜줘'
        assert service.step(compound,user).diagnostic['reason_code']=='outcome_contract_changed'


def test_wrong_state_can_bind_its_subject_without_becoming_completion_evidence(compound):
    contract=OutcomeGoal(clauses=outcome.legacy_compound(compound.goal),seal='0'*64)
    indexed={'c0':compound.screen.nodes[0],'c1':compound.screen.nodes[1]}
    facts={'c0':('c0','visible',None),'c1.checked_true':('c1','checked',True)}
    evidence,bindings=outcome.expand_bindings(contract,{'r0':'c0','r1':'c1.checked_true'},indexed,facts,compound.screen.package)
    assert evidence['proof']==['c0']
    assert bindings['r1'].label=='자동 동기화' and bindings['r1'].checked is False


@pytest.mark.parametrize('selected', [True, False])
def test_page_can_bind_a_native_tab_but_only_its_selected_state_proves_arrival(selected):
    request = unseeded('보관함을 보여줘', [dict(label='보관함', role='tab', clickable=True, selected=selected)])
    contract = OutcomeGoal(clauses=[OutcomeClause(kind='page', labels=['보관함'])], seal='0'*64)
    fact = 'c0.selected_' + str(selected).lower()
    evidence, bindings = outcome.expand_bindings(contract, {'r0': fact}, {'c0': request.screen.nodes[0]},
                                                  {fact: ('c0', 'selected', selected)}, request.screen.package)
    proof = TaskCompletion(checks=[bindings['r0']])
    assert bindings['r0'].selected is True
    assert evidence['proof'] == ([fact] if selected else [])
    assert outcome.covers(contract.clauses, proof, request.screen, observed=True) == selected


def test_public_title_uses_the_same_read_only_binder_from_goal_first(monkeypatch):
    request=unseeded('예전에 저장한 것들을 보여줘',[dict(label='보관함',role='text',native_heading=True)])
    clauses=[OutcomeClause(kind='page',labels=['Saved items'])]
    request.state.outcome_goal=OutcomeGoal(clauses=clauses,seal=outcome._seal(request,clauses))
    request.planning_mode='goal_first'
    service=AppTaskService()
    modes=[]
    def plan(current,*args,**kwargs):
        modes.append(current.planning_mode)
        result=outcome.envelope(current)
        bound=TaskResultCheck(package=request.screen.package,label='보관함',role='text')
        result.outcome_bindings={'r0':bound};result.completion=TaskCompletion(checks=[bound])
        result.status='confirm_result'
        return result
    monkeypatch.setattr(service,'_plan',plan)
    result=service.step(request)
    assert modes==['result_only']
    assert result.status=='confirm_result' and result.diagnostic['goal_assessment']=='satisfied'
    assert result.diagnostic['planning_mode']=='goal_first'
