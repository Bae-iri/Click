"""An explicit destination request must not become a setting mutation or a generic Settings tie."""
import pytest
from app.main import create_app
from tests.helpers import authenticated_client
from tests.test_goal_preparation import Flow


@pytest.mark.parametrize(('text','expected'),[
    ('연결 설정 열기 도와줘','connections'),
    ('연결 설정 화면으로 이동해줘','connections'),
    ('휴대폰 연결 설정에 들어가고 싶어','connections'),
    ('편안하게 화면 보기 설정 열기 도와줘','eye_comfort'),
    ('방해 금지 설정 열기 하고 싶어','do_not_disturb'),
    ('방해 금지 설정 화면으로 이동해줘','do_not_disturb'),
    ('음량 설정 열기 하고 싶어','volume'),
    ('음량 설정 화면으로 이동해줘','volume'),
])
def test_explicit_navigation_resolves_without_asking_for_a_change(text,expected):
    app=create_app();flow=Flow(authenticated_client(app),app,text)
    response=flow.resolve()
    assert response['resolution_status']=='confirmation_required'
    assert response['goal']['goal_type']=='settings.navigation.'+expected
    assert response['goal']['slots']['action']=='open'
    assert response['preparation']['question'] is None


@pytest.mark.parametrize(('text','expected'),[
    ('방해 금지 켜줘','settings.do_not_disturb.enable'),
    ('방해 금지 꺼줘','settings.do_not_disturb.disable'),
    ('편안하게 화면 보기 켜줘','settings.eye_comfort.enable'),
    ('편안하게 화면 보기 꺼줘','settings.eye_comfort.disable'),
])
def test_explicit_value_change_keeps_its_original_goal(text,expected):
    app=create_app();flow=Flow(authenticated_client(app),app,text)
    assert flow.resolve()['goal']['goal_type']==expected
