"""Advisory later-step labels: optional, sanitized, never an action by themselves."""
from app.services.app_task import AppTaskService
from tests.test_app_task import request, sender


def test_planned_steps_are_optional_and_old_shaped_replies_still_plan():
    result=AppTaskService(sender()).step(request())
    assert result.status=='proposed' and result.planned_steps==[]
    send=sender();AppTaskService(send).step(request())
    payload=send.call_args.args[0]
    props=payload['generationConfig']['responseJsonSchema']['properties']
    assert props['planned_steps']['maxItems']==3
    assert 'planned_steps' not in payload['generationConfig']['responseJsonSchema']['required']


def test_planned_steps_keep_public_labels_in_order_and_stop_at_unsafe_ones():
    result=AppTaskService(sender(planned_steps=['목록 보기','  고급 설정 ','결제하기','도움말'])).step(request())
    assert result.status=='proposed' and result.action is not None
    assert [s.label for s in result.planned_steps]==['목록 보기','고급 설정']
    assert all(s.tool=='tap' for s in result.planned_steps)
    assert result.diagnostic['planned_step_count']==2


def test_planned_steps_drop_visible_labels_duplicates_and_bad_shapes():
    # '검색' is on the current screen: grounding it later would tap this screen's control again.
    assert AppTaskService(sender(planned_steps=['검색','목록'])).step(request()).planned_steps==[]
    assert [s.label for s in AppTaskService(sender(planned_steps=['목록','목록'])).step(request()).planned_steps]==['목록']
    assert AppTaskService(sender(planned_steps='목록')).step(request()).planned_steps==[]
    assert AppTaskService(sender(planned_steps=[{'label':'목록'}])).step(request()).planned_steps==[]


def test_non_action_replies_never_carry_planned_steps():
    result=AppTaskService(sender(tool='ask',target='none',planned_steps=['목록'])).step(request())
    assert result.status=='needs_input' and result.planned_steps==[]
