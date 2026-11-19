"""HTTP recovery preserves the user's civil goal without authenticating for them."""
from copy import deepcopy
from uuid import uuid4
import time
import pytest
from app.services import public_guide_policy as policy
from tests.test_public_guide import login, screen, PATH  # noqa: F401
from tests.test_gov24_navigation import CivilFlow, view

VERSION = 'gov24-recovery.v1'


def command(flow, name, **kwargs):
    return flow.command(name, recovery_version=VERSION, **kwargs)


def home(*keys, signed_in=False):
    result = view('marker_home', *keys)
    result['auth_state'] = 'signed_in' if signed_in else 'signed_out'
    if not signed_in:
        result['nodes'] += screen('entry_login')['nodes']
    return result


@pytest.mark.parametrize('goal', list(policy.CIVIL_GRAPHS))
def test_login_prerequisite_resumes_each_original_service_only_after_signed_in_home(login, goal):
    graph = policy.CIVIL_GRAPHS[goal]
    civil = CivilFlow(login.client, login.app, '정부24 '+graph['label']+' 안내 화면 찾아줘')
    prep = civil.resolve()['preparation']
    assert prep['public_guide_goal']['goal_type'] == goal
    before = command(civil, 'start', goal_resolution_id=prep['id'], screen=home())
    assert before['target_node_id'] == 'native_entry_login'
    methods = command(civil, 'observe', screen=screen('method_simple','method_joint'))
    picked = command(civil, 'observe', screen=screen('method_simple','method_joint'),
                     question_id=methods['question']['id'], choice='method_simple')
    assert picked['target_node_id'] == 'native_method_simple'
    private = command(civil, 'observe', screen=screen(status='handoff', reason='credential_input'))
    assert private['status'] == 'IN_PROGRESS' and private['subgoal'] == 'await_login'
    assert private['target_node_id'] is None and private['question'] is None
    assert private['diagnostic']['status'] == 'WAITING'
    assert '원래 요청' in private['message']
    assert command(civil, 'snapshot')['subgoal'] == 'await_login'
    target = next(edge['target_option'] for edge in graph['transitions']
                  if edge['target_option'] not in {'nav_services','nav_frequent'})
    unknown = command(civil, 'observe', screen=view(target))
    assert unknown['subgoal'] == 'await_login' and unknown['target_node_id'] is None
    resumed = command(civil, 'observe', screen=home(target, signed_in=True))
    assert resumed['status'] == 'IN_PROGRESS' and resumed['target_node_id'] == 'node_'+target
    session = civil.client.get('/api/v1/agent/sessions/'+civil.session).json()
    assert session['public_guide_goal_type'] == goal
    arrived = command(civil, 'observe', screen=view(*graph['completion']['any_of'][0]))
    assert arrived['outcome'] == 'verified_navigation'


def test_signed_in_home_skips_login_and_logout_is_never_a_target(login):
    civil = CivilFlow(login.client, login.app, '정부24 주민등록등본 안내 화면 찾아줘')
    prep = civil.resolve()['preparation']
    response = command(civil, 'start', goal_resolution_id=prep['id'], screen=home('nav_resident', signed_in=True))
    assert response['target_node_id'] == 'node_nav_resident'


def test_standalone_login_waits_and_verifies_explicit_home_state(login):
    command(login,'start',goal_resolution_id=login.prepared['id'],screen=screen('method_simple'))
    waiting = command(login,'observe',screen=screen(status='handoff',reason='external_auth'))
    assert waiting['status'] == 'IN_PROGRESS' and waiting['subgoal'] == 'await_login'
    # No home state is inferred from disappearing controls or a certificate choice.
    assert command(login,'observe',screen=screen(status='unrecognized'))['subgoal'] == 'await_login'
    signed_in = home(signed_in=True)
    signed_in['nodes'] = []  # Home state can be public evidence without a login action.
    completed = command(login,'observe',screen=signed_in)
    assert completed['status'] == 'COMPLETED' and completed['target_node_id'] is None


def test_loading_after_login_choice_waits_and_then_accepts_provider_page(login):
    q = command(login,'start',goal_resolution_id=login.prepared['id'],screen=screen('method_simple','method_joint'))['question']
    command(login,'observe',screen=screen('method_simple','method_joint'),question_id=q['id'],choice='method_simple')
    waiting = command(login,'observe',screen=screen(status='unrecognized'))
    assert waiting['status'] == 'IN_PROGRESS' and waiting['subgoal'] == 'await_public'
    providers = command(login,'observe',screen=screen('provider_kakao','provider_naver'))
    assert providers['subgoal'] == 'choose_provider' and providers['question']


def test_choice_survives_layout_change_but_uses_fresh_native_identity(login):
    before = screen('method_simple','method_joint')
    q = command(login,'start',goal_resolution_id=login.prepared['id'],screen=before)['question']
    after = deepcopy(before)
    after.update(observation_id=uuid4().hex,captured_at_ms=int(time.time()*1000))
    after['nodes'][0]['node_id'] = 'fresh-native-button'
    after['nodes'][0]['bounds'][1] += 1
    response = command(login,'observe',screen=after,question_id=q['id'],choice='method_simple')
    assert response['target_node_id'] == 'fresh-native-button'
    assert list(response['target_bounds']) == after['nodes'][0]['bounds']


def test_reordered_choices_require_a_new_question_so_numbered_answers_cannot_target_the_wrong_method(login):
    before=screen('method_simple','method_joint')
    question=command(login,'start',goal_resolution_id=login.prepared['id'],screen=before)['question']
    response=command(login,'observe',screen=screen('method_joint','method_simple'),question_id=question['id'],choice='method_simple')
    assert response['target_node_id'] is None and response['question'] is not None


def test_private_handoff_can_be_cancelled_without_later_resumption(login):
    command(login,'start',goal_resolution_id=login.prepared['id'],screen=screen('method_simple'))
    command(login,'observe',screen=screen(status='handoff',reason='consent_required'))
    assert command(login,'cancel')['status'] == 'CANCELLED'
    response = login.client.post(PATH,json=dict(request_id=uuid4().hex,session_id=login.session,
        expected_version=login.version,command='observe',recovery_version=VERSION,screen=screen('method_simple')))
    assert response.status_code == 409


def test_private_screen_cannot_carry_authentication_state(login):
    view = screen(status='handoff',reason='credential_input')
    view['auth_state'] = 'signed_in'
    response = login.client.post(PATH,json=dict(request_id=uuid4().hex,session_id=login.session,
        expected_version=0,command='start',recovery_version=VERSION,goal_resolution_id=login.prepared['id'],screen=view))
    assert response.status_code == 422


def test_ambiguous_signed_in_home_cannot_bypass_prerequisite(login):
    civil = CivilFlow(login.client,login.app,'정부24 주민등록등본 화면 찾아줘')
    prep = civil.resolve()['preparation']
    command(civil,'start',goal_resolution_id=prep['id'],screen=home())
    contradictory = home('nav_resident')
    contradictory['auth_state'] = 'signed_in'  # Still contains Login.
    response = command(civil,'observe',screen=contradictory)
    assert response['subgoal'] == 'await_login' and response['target_node_id'] is None
