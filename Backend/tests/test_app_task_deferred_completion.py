"""A destination title that is still this screen's menu entry becomes a future-only candidate."""
from app.schemas.app_task import TaskSubgoal
from app.services.app_task import AppTaskService
from tests.test_app_task import request, sender


def check(label,target='none'):
    return [dict(target=target,label=label,app='none',checked='unknown',selected='unknown')]


def test_menu_label_prediction_is_sent_as_future_only_candidate():
    result=AppTaskService(sender(completion_checks=check('검색'))).step(request())
    assert result.status=='proposed' and result.completion is None
    assert result.diagnostic['completion_grounding']=='deferred_actionable_proof'
    assert result.diagnostic['completion_deferred_future'] is True
    assert [c.label for c in result.deferred_completion.checks]==['검색']


def test_an_intrinsically_unsafe_prediction_is_still_dropped():
    # Bound to the visible button itself: a control can never be its own destination proof.
    result=AppTaskService(sender(completion_checks=check('','c0'))).step(request())
    assert result.completion is None and result.deferred_completion is None


def test_a_safe_future_prediction_stays_the_normal_candidate():
    result=AppTaskService(sender()).step(request())
    assert result.completion is not None and result.deferred_completion is None


def test_result_only_and_failures_never_carry_a_deferred_candidate():
    req=request();req.planning_mode='result_only'
    req.state.subgoals=[TaskSubgoal(id='g0',title='검색 열기',status='verified')]
    assert AppTaskService(sender(tool='confirm',target='none',completion_checks=check('검색'))).step(req).deferred_completion is None
    broken=sender();broken.return_value.status_code=500
    assert AppTaskService(broken).step(request()).deferred_completion is None
