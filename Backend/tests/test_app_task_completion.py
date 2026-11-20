"""Only typed evidence-contract incompatibility is recoverable without a new plan."""
from copy import deepcopy
from dataclasses import asdict

import pytest
from pydantic import ValidationError

from app.schemas.app_task import TaskCompletion, TaskNode, TaskResultCheck
from app.services.app_task_completion import bind_completion_candidate


PACKAGE='org.example.reader'
SELECTED='org.example.selected'


def control(role='button',**updates):
    data=dict(id='node_0_1',label='목록',resource=PACKAGE+':id/destination',role=role,
        bounds=(0,0,100,100),clickable=role=='button',checked=None)
    data.update(updates)
    return TaskNode(**data)


def clause(**updates):
    result=dict(target='c0',label='',app='none',checked='unknown',selected='unknown')
    result.update(updates)
    return result


def bind(checks, *, node=None, bound=None, selected=()):
    return bind_completion_candidate(checks,current_package=PACKAGE,selected_packages=selected,
        indexed_controls={'c0':node or control()},bound_completion=bound)


@pytest.mark.parametrize('checks',[
    [clause(checked='true')],
    [clause(checked='false')],
])
def test_native_check_flag_on_a_button_defers_the_whole_candidate_without_repair(checks):
    original=deepcopy(checks)
    result=bind(checks)
    assert result.completion is None and result.deferred_reason=='completion_contract_invalid'
    assert result.validation_errors==({'field':'checks.index.checked','type':'value_error'},)
    assert checks==original


def test_selection_flag_on_text_defers_without_removing_the_requested_state():
    checks=[clause(selected='true')];original=deepcopy(checks)
    result=bind(checks,node=control('text'))
    assert result.completion is None
    assert result.validation_errors==({'field':'checks.index.selected','type':'value_error'},)
    assert checks==original


def test_one_bad_flag_never_returns_a_valid_subset_of_a_conjunction():
    checks=[clause(target='none',label='목적 화면'),clause(checked='true')]
    result=bind(checks)
    assert result.completion is None and result.deferred_reason
    assert len(checks)==2 and checks[1]['checked']=='true'


def test_native_desired_state_stays_explicit_even_when_it_differs_from_the_current_value():
    result=bind([clause(checked='false')],node=control('toggle',checked=True))
    assert result.deferred_reason is None
    check=result.completion.checks[0]
    assert check.checked is False and check.role=='toggle'
    assert check.label=='목록' and check.resource==PACKAGE+':id/destination'


def test_unseen_public_label_stays_a_provisional_candidate_without_invented_native_state():
    result=bind([clause(target='none',label='나중에 보일 목적 제목')])
    assert result.deferred_reason is None
    check=result.completion.checks[0]
    assert check.label=='나중에 보일 목적 제목' and check.role=='' and check.resource==''
    assert check.checked is None and check.selected is None


def test_selected_cross_app_conjunction_is_deferred_as_a_whole_not_collapsed_to_one_app():
    checks=[clause(target='none',label='현재 결과'),
        clause(target='none',label='다른 결과',app=SELECTED)]
    original=deepcopy(checks);result=bind(checks,selected=(SELECTED,))
    assert result.completion is None and result.deferred_reason=='completion_contract_invalid'
    assert result.validation_errors==({'field':'checks','type':'value_error'},)
    assert checks==original


@pytest.mark.parametrize('unsafe,code',[
    (clause(label='PRIVATE@example.com'),'completion_label'),
    (clause(target='invented'),'completion_target'),
    (clause(target='none',label='목적 제목',app='org.private.bank'),'completion_app'),
    (clause(target='none',label='목적 제목',checked='true'),'ungrounded_result_state'),
    (clause(label='다른 관찰 제목'),'completion_control'),
    (clause(checked='PRIVATE'),'expected_state'),
    (dict(target='c0',label='목록'),'completion_shape'),
])
def test_later_strict_failure_cannot_be_hidden_by_an_earlier_recoverable_contract_error(unsafe,code):
    with pytest.raises(ValueError,match='^'+code+'$'):
        bind([clause(checked='true'),unsafe])


@pytest.mark.parametrize('value',[None,{},'PRIVATE',list(range(9))])
def test_malformed_candidate_container_is_still_a_strict_plan_error(value):
    with pytest.raises(ValueError,match='^completion$'):
        bind(value)


def test_bound_completion_stays_immutable_and_cannot_be_soft_deferred_or_replaced():
    existing=TaskCompletion(checks=[TaskResultCheck(package=PACKAGE,label='元の結果')])
    result=bind([],bound=existing)
    assert result.completion==existing and result.deferred_reason is None
    for change in ([clause(checked='true')],[clause(target='none',label='別の結果')]):
        with pytest.raises(ValueError,match='^changed_completion$'):
            bind(change,bound=existing)


def test_no_candidate_has_no_deferred_error_and_never_makes_an_empty_contract():
    result=bind([])
    assert result.completion is None and result.deferred_reason is None and result.validation_errors==()


def test_deferred_metadata_has_only_closed_field_type_and_reason_values():
    result=bind([clause(checked='true') for _ in range(8)])
    assert len(result.validation_errors)==4
    report=str(asdict(result))
    assert '목록' not in report and PACKAGE not in report and 'Value error' not in report
    assert 'input' not in report and 'ctx' not in report and 'url' not in report


def test_unexpected_typed_context_error_is_strict_not_a_recoverable_native_flag_error():
    with pytest.raises(ValidationError):
        bind_completion_candidate([clause(checked='true')],current_package='INVALID',
            selected_packages=(),indexed_controls={'c0':control()})
