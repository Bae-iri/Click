"""Only bound closed goal decisions may accompany actual request measurements."""
import pytest
from scripts.serve_meta_controller_public_apps_experiment import _diagnostic_metadata
from scripts.audit_meta_controller_public_apps_provider import _context_metadata
from tests.test_meta_controller_public_apps_experiment import request,response


def test_goal_first_decision_survives_provider_and_client_audit_boundaries():
    req=request();res=response(req);res.diagnostic.update(planning_mode='goal_first',goal_assessment='satisfied')
    record={};_diagnostic_metadata(req,res,record,12)
    assert record['planning_mode']=='goal_first' and record['goal_assessment']=='satisfied'
    copied,invalid=_context_metadata(record)
    assert copied['goal_assessment']=='satisfied' and copied['planning_mode']=='goal_first' and not invalid


@pytest.mark.parametrize('bad',[True,1,'PRIVATE',None])
def test_goal_decision_is_never_coerced_or_leaked(bad):
    req=request();res=response(req);res.diagnostic.update(planning_mode='goal_first',goal_assessment=bad)
    record={};_diagnostic_metadata(req,res,record,12);assert 'goal_assessment' not in record
    copied,invalid=_context_metadata(dict(goal_assessment=bad));assert not copied and invalid==['goal_assessment']


def test_foreign_response_cannot_claim_goal_assessment_for_this_request():
    req=request();res=response(req);res.task_id='unrelated';res.diagnostic.update(planning_mode='goal_first',goal_assessment='satisfied')
    record={};_diagnostic_metadata(req,res,record,12)
    assert 'planning_mode' not in record and 'goal_assessment' not in record
