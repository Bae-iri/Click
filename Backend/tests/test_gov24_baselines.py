"""Six first-run public task graphs work without learned routes or provider calls."""
from copy import deepcopy
from sqlalchemy import select
import pytest
from app.models.runtime_event import RuntimeEvent
from app.models.graph_proposal import GraphProposal
from app.services.public_guide_policy import CIVIL_GRAPHS, CIVIL_OPTIONS
from app.services.gov24_graphs import GovernmentGraphLearningService
from tests.test_gov24_navigation import CivilFlow, grant, view
from tests.test_public_guide import login  # noqa: F401


@pytest.mark.parametrize('goal_type', CIVIL_GRAPHS)
def test_each_basic_service_selects_its_own_visible_tile_on_first_use(login, goal_type):
    graph = CIVIL_GRAPHS[goal_type]
    f = CivilFlow(login.client, login.app, graph['label'] + ' 안내해줘')
    prep = f.resolve()['preparation']
    assert prep['public_guide_goal']['goal_type'] == goal_type
    target = graph['transitions'][0]['target_option']
    before = view('marker_home', 'nav_services', target)
    response = f.command('start', goal_resolution_id=prep['id'], screen=before)
    assert response['target_node_id'] == 'node_' + target
    assert response['baseline_graph_id'] == graph['task_id']
    assert response['graph_state'] == 'public_menu'
    assert response['question'] is None
    assert graph['label'] in response['service_label']
    after = view(*graph['completion']['any_of'][0])
    complete = f.command('observe', screen=after, action=dict(guide_id=response['guide_id'],
        observation_id=before['observation_id'], node_id=response['target_node_id'], occurred_at_ms=before['captured_at_ms']))
    assert complete['outcome'] == 'verified_navigation'
    assert complete['graph_state'] == 'service_entry'
    assert '직접' in complete['message']
    assert complete['target_node_id'] is None
    with f.app.state.session_factory() as db:
        event = db.scalar(select(RuntimeEvent).where(RuntimeEvent.session_id == f.session).order_by(RuntimeEvent.sequence))
        assert event.payload['public_guide']['reason'] == 'baseline_graph_selected'


@pytest.mark.parametrize('goal_type', CIVIL_GRAPHS)
def test_basic_service_uses_services_list_then_requested_tile(login, goal_type):
    graph = CIVIL_GRAPHS[goal_type]
    f = CivilFlow(login.client, login.app, '정부24 ' + graph['label'] + ' 화면 찾아줘')
    user, now = grant(f)
    prep = f.resolve()['preparation']
    before = view('marker_home', 'nav_services')
    first = f.command('start', goal_resolution_id=prep['id'], screen=before)
    assert first['target_node_id'] == 'node_nav_services'
    tile = graph['transitions'][0]['target_option']
    middle = view('marker_services', tile)
    second = f.command('observe', screen=middle, action=dict(guide_id=first['guide_id'],
        observation_id=before['observation_id'], node_id=first['target_node_id'], occurred_at_ms=before['captured_at_ms']))
    assert second['graph_state'] == 'services'
    assert second['target_node_id'] == 'node_' + tile
    done = f.command('observe', screen=view(*graph['completion']['any_of'][0]), action=dict(guide_id=second['guide_id'],
        observation_id=middle['observation_id'], node_id=second['target_node_id'], occurred_at_ms=middle['captured_at_ms']))
    assert done['outcome'] == 'verified_navigation'
    learned = GovernmentGraphLearningService(f.app.state.session_factory).learn_session(f.session, user, now=now)
    assert learned['status'] == 'DRAFT_CREATED'
    with f.app.state.session_factory() as db:
        candidate = db.get(GraphProposal, learned['proposal_id']).change['graph']
        assert candidate['definition']['goal_type'] == goal_type
        assert len(candidate['transitions']) == 2


def test_wrong_service_or_generic_apply_button_cannot_complete_the_requested_task(login):
    f = CivilFlow(login.client, login.app, '건축물대장 화면 찾아줘')
    prep = f.resolve()['preparation']
    response = f.command('start', goal_resolution_id=prep['id'], screen=view('marker_resident', 'boundary_apply'))
    assert response['outcome'] != 'verified_navigation'
    assert response['target_node_id'] is None


def test_v3_client_without_multi_service_support_gets_an_update_message(login):
    class OldCivil(CivilFlow):
        def payload(self, **kwargs):
            value = super().payload(**kwargs)
            value['device_context']['capabilities']['gov24_services'] = False
            return value
    result = OldCivil(login.client, login.app, '정부24 건축물대장 화면 찾아줘').resolve()
    assert result['resolution_status'] == 'unsupported'
    assert '최신 앱' in result['message']
