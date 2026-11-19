"""Refreshing a Government24 screen must not exhaust actual navigation attempts."""
from time import time
from uuid import uuid4
from unittest.mock import Mock

import pytest

from app.schemas.public_guide import PublicGuideRequest
from app.services.public_guide import PublicGuideService
from app.services import public_guide_policy as policy


def screen(*keys, civil=False, status='public', reason=None):
    options = policy.CIVIL_OPTIONS if civil else policy.OPTIONS
    result = dict(status=status, package_name=policy.POLICY['package'], observation_id=uuid4().hex,
                  captured_at_ms=int(time()*1000), width=1080, height=2400,
                  nodes=[dict(node_id='node_'+key, option_id=key, resource_id='', label=options[key]['label'],
                              clickable=options[key]['group'] != 'marker', bounds=[10,10+i*100,500,90+i*100])
                         for i,key in enumerate(keys)])
    if reason:
        result['handoff_reason'] = reason
    return result


class Guide:
    def __init__(self, civil=False):
        self.civil = civil
        self.state = dict(goal=dict(goal_type=policy.CIVIL['goal_type'] if civil else 'gov24.login'), steps=0)
        self.service = PublicGuideService(None, None, selector=Mock())
        self.version = 0

    def observe(self, view, **kwargs):
        request = PublicGuideRequest(request_id=uuid4().hex, session_id='original-government24-request',
                                     expected_version=self.version, command='observe', recovery_version='gov24-recovery.v1', screen=view, **kwargs)
        result, evidence, self.state = self.service.decide(request, self.state, int(time()))
        self.version = result.session_version
        return result, evidence


@pytest.mark.parametrize('civil', [False, True])
def test_repeated_fresh_reads_of_the_same_public_menu_do_not_end_the_request(civil):
    guide = Guide(civil)
    for _ in range(policy.POLICY['max_turns']+6):
        view = screen('nav_resident', civil=True) if civil else screen('method_simple', 'method_joint')
        # Native node identities and layout can change without a new menu.
        view['nodes'][0]['node_id'] = uuid4().hex
        result, _ = guide.observe(view)
        assert result.status == 'IN_PROGRESS'
        assert result.target_node_id is not None or result.question is not None
    assert guide.state['navigation_turns'] == 1


@pytest.mark.parametrize('civil', [False, True])
@pytest.mark.parametrize('status', ['unrecognized', 'outside_app'])
def test_waiting_for_a_readable_screen_does_not_use_navigation_budget(civil, status):
    guide = Guide(civil)
    for _ in range(policy.POLICY['max_turns']+6):
        result, _ = guide.observe(screen(status=status))
        assert result.status == 'IN_PROGRESS' and result.target_node_id is None
    public = screen('nav_resident', civil=True) if civil else screen('method_simple')
    result, _ = guide.observe(public)
    assert result.status == 'IN_PROGRESS'


def spend_budget(guide):
    choices = (sorted({edge['target_option'] for edge in policy.CIVIL_GRAPHS[guide.state['goal']['goal_type']]['transitions']})[:2]
               if guide.civil else ('method_simple', 'method_joint'))
    for index in range(policy.POLICY['max_turns']):
        result, _ = guide.observe(screen(choices[index%2], civil=guide.civil))
        assert result.status == 'IN_PROGRESS'
    assert guide.state['navigation_turns'] == policy.POLICY['max_turns']


@pytest.mark.parametrize('civil', [False, True])
def test_actual_navigation_loops_still_have_a_limit_and_explain_the_reason(civil):
    guide = Guide(civil)
    spend_budget(guide)
    key = sorted({edge['target_option'] for edge in policy.CIVIL_GRAPHS[guide.state['goal']['goal_type']]['transitions']})[0] if civil else 'method_simple'
    result, evidence = guide.observe(screen(key, civil=civil))
    assert result.status == 'BLOCKED' and result.target_node_id is None
    assert evidence['reason'] == 'navigation_limit'


@pytest.mark.parametrize('civil', [False, True])
def test_authentication_boundary_has_priority_over_an_exhausted_navigation_budget(civil):
    guide = Guide(civil)
    spend_budget(guide)
    result, evidence = guide.observe(screen(status='handoff', reason='credential_input'))
    assert result.status == 'IN_PROGRESS' and result.subgoal in {'await_login', 'await_public'}
    assert result.target_node_id is None and result.question is None


@pytest.mark.parametrize('goal_type', list(policy.CIVIL_GRAPHS))
def test_observed_service_arrival_is_verified_even_after_all_navigation_attempts(goal_type):
    guide = Guide(True)
    guide.state['goal']['goal_type'] = goal_type
    spend_budget(guide)
    required = policy.CIVIL_GRAPHS[goal_type]['completion']['any_of'][0]
    result, _ = guide.observe(screen(*required, civil=True))
    assert result.status == 'COMPLETED' and result.outcome == 'verified_navigation'

