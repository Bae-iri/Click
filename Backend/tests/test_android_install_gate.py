"""A rollout must not install the strict client before the server has its protocol."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock
import zipfile
import pytest

SOURCE=Path(__file__).resolve().parents[2]/'Frontend/scripts/install_verified_debug.py'
spec=importlib.util.spec_from_file_location('install_gate',SOURCE)
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

def package(tmp_path,protocol='execution-state.v1',recovery=False):
    path=tmp_path/'test.apk'
    with zipfile.ZipFile(path,'w') as archive:
        if protocol:
            archive.writestr('assets/execution_state_contract.json',json.dumps({'version':protocol}))
            identity={'api_base_url':'https://test.example','task_termination_version':'task-termination.v1'}
            if recovery:identity['gov24_recovery_version']='gov24-recovery.v1'
            archive.writestr('assets/click_release_identity.json',json.dumps(identity))
    return path

def schema():
    return json.loads((Path(__file__).parents[1]/'docs/openapi.json').read_text())

@pytest.mark.parametrize('fault',['old_server','wrong_version','missing_session_state','partial_response','missing_decline_api','missing_decline_command'])
def test_incompatible_schema_can_never_reach_adb_install(tmp_path,fault):
    document=schema();models=document['components']['schemas']
    if fault=='old_server':models.pop('ExecutionContext');models['AgentCommandRequest']['properties'].pop('execution_context')
    if fault=='wrong_version':models['ExecutionContext']['properties']['protocol_version']={'const':'execution-state.v2'}
    if fault=='missing_session_state':models['AgentSessionResponse']['properties'].pop('execution_state')
    if fault=='partial_response':models['ExecutionState']['properties'].pop('phase')
    if fault=='missing_decline_api':document['paths'].pop('/api/v1/voice-requests/{request_id}/cancel')
    if fault=='missing_decline_command':models['GuideCommandResponse']['properties']['command_type']['enum'].remove('cancelled')
    runner=Mock()
    with pytest.raises(gate.IncompatibleServer):gate.install(package(tmp_path),document,'adb','device',runner,base_url='https://test.example')
    runner.assert_not_called()

def test_matching_protocol_can_install_without_clearing_user_data(tmp_path):
    runner=Mock(return_value=SimpleNamespace(returncode=0,stdout='Success\n'))
    result=gate.install(package(tmp_path),schema(),'adb','device',runner,base_url='https://test.example')
    assert result['compatible']
    assert runner.call_args.args[0][:6]==['adb','-s','device','install','-r','-t']

def test_existing_release_is_explicitly_marked_legacy_not_protocol_complete(tmp_path):
    document=schema();document['components']['schemas'].pop('ExecutionContext')
    document['components']['schemas']['AgentCommandRequest']['properties'].pop('execution_context')
    result=gate.check_compatibility(package(tmp_path,None),document)
    assert result=={'compatible':True,'required_protocol':None,'mode':'legacy_release'}


def test_a_different_render_host_cannot_authorize_this_apk_installation(tmp_path):
    runner=Mock()
    with pytest.raises(gate.IncompatibleServer,match='API origin'):
        gate.install(package(tmp_path),schema(),'adb','device',runner,base_url='https://another.example')
    runner.assert_not_called()


@pytest.mark.parametrize('fault',['old_server','missing_wait','missing_request_version','missing_auth_state','wrong_recovery_version'])
def test_government24_recovery_requires_matching_server_before_install(tmp_path,fault):
    from app.main import create_app
    document=schema() if fault=='old_server' else create_app().openapi()
    models=document['components']['schemas']
    if fault=='old_server':
        models.get('PublicGuideRequest',{}).get('properties',{}).pop('recovery_version',None)
    if fault=='missing_wait':models['PublicGuideResponse']['properties']['subgoal']['enum'].remove('await_login')
    if fault=='missing_request_version':models['PublicGuideRequest']['properties'].pop('recovery_version')
    if fault=='missing_auth_state':models['PublicGuideScreen']['properties'].pop('auth_state')
    if fault=='wrong_recovery_version':models['PublicGuideResponse']['properties']['recovery_version']['const']='gov24-recovery.v2'
    runner=Mock()
    with pytest.raises(gate.IncompatibleServer,match='gov24-recovery.v1'):
        gate.install(package(tmp_path,recovery=True),document,'adb','device',runner,base_url='https://test.example')
    runner.assert_not_called()


def test_matching_government24_recovery_schema_allows_data_preserving_install(tmp_path):
    from app.main import create_app
    runner=Mock(return_value=SimpleNamespace(returncode=0,stdout='Success\n'))
    assert gate.install(package(tmp_path,recovery=True),create_app().openapi(),'adb','device',runner,base_url='https://test.example')['compatible']
    assert '-r' in runner.call_args.args[0] and 'uninstall' not in runner.call_args.args[0]
