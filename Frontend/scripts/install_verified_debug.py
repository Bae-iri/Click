"""Refuse to install an APK whose execution protocol is absent from the target server.

Example: python install_verified_debug.py --apk app.apk --base-url https://example.com --check-only
The check reads public OpenAPI only. It does not change accounts, tasks, or device settings.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
from urllib.request import Request, urlopen
from urllib.parse import urlsplit
import zipfile

class IncompatibleServer(ValueError):
    pass

def declares_recovery(schema):
    return (schema.get('const') == 'gov24-recovery.v1' or 'gov24-recovery.v1' in schema.get('enum', [])
            or any(declares_recovery(choice) for choice in schema.get('anyOf', [])))

def check_compatibility(apk, document, base_url=None):
    schemas=document.get('components',{}).get('schemas',{})
    request=schemas.get('AgentCommandRequest',{}).get('properties',{})
    if not request or '/api/v1/agent/commands' not in document.get('paths',{}):
        raise IncompatibleServer('Agent command API is missing')
    with zipfile.ZipFile(apk) as package:
        try:package.getinfo('assets/execution_state_contract.json')
        except KeyError:required=None
        else:
            with package.open('assets/execution_state_contract.json') as asset:
                content=asset.read(65537)
                if len(content)>65536:raise IncompatibleServer('Execution policy asset is too large')
                required=json.loads(content).get('version')
                if not isinstance(required,str) or not required:raise IncompatibleServer('APK execution policy is invalid')
    if required is None:
        if 'execution_context' in request:raise IncompatibleServer('Legacy release is not verified for the new server rollout')
        return {'compatible':True,'required_protocol':None,'mode':'legacy_release'}
    with zipfile.ZipFile(apk) as package:
        try:identity=json.loads(package.read('assets/click_release_identity.json'))
        except KeyError:raise IncompatibleServer('APK release identity is missing; rebuild before installation')
    actual=identity.get('api_base_url','').rstrip('/')
    if not base_url or actual!=base_url.rstrip('/'):
        raise IncompatibleServer('APK API origin differs from the server being checked')
    protocol=schemas.get('ExecutionContext',{}).get('properties',{}).get('protocol_version',{})
    versions=protocol.get('enum',[])+([protocol['const']] if 'const' in protocol else [])
    if required not in versions or 'execution_context' not in request:
        raise IncompatibleServer('Server upgrade is not deployed: APK requires '+required)
    for name in ['AgentCommandResponse','AgentGoalResolutionResponse','AgentSessionResponse']:
        if 'execution_state' not in schemas.get(name,{}).get('properties',{}):
            raise IncompatibleServer('Server execution responses are incomplete: '+name)
    required_fields={'session_id','session_version','episode_id','request_id','command_type','task_status','phase','source'}
    if not required_fields.issubset(schemas.get('ExecutionState',{}).get('properties',{})):
        raise IncompatibleServer('Server execution state schema is incomplete')
    if identity.get('task_termination_version'):
        command=schemas.get('GuideCommandResponse',{}).get('properties',{}).get('command_type',{})
        reasons=schemas.get('CancelVoiceRequest',{}).get('properties',{}).get('reason',{})
        if (identity['task_termination_version']!='task-termination.v1'
                or 'cancelled' not in command.get('enum',[])
                or not {'USER_DECLINED','USER_ABORTED'}.issubset(reasons.get('enum',[]))
                or '/api/v1/voice-requests/{request_id}/cancel' not in document.get('paths',{})):
            raise IncompatibleServer('Server upgrade is not deployed: APK requires task-termination.v1')
    if identity.get('gov24_recovery_version'):
        response=schemas.get('PublicGuideResponse',{}).get('properties',{})
        public_request=schemas.get('PublicGuideRequest',{}).get('properties',{})
        screen=schemas.get('PublicGuideScreen',{}).get('properties',{})
        if (identity['gov24_recovery_version']!='gov24-recovery.v1'
                or not {'await_login','await_public'}.issubset(response.get('subgoal',{}).get('enum',[]))
                or not declares_recovery(response.get('recovery_version',{}))
                or not declares_recovery(public_request.get('recovery_version',{})) or 'auth_state' not in screen):
            raise IncompatibleServer('Server upgrade is not deployed: APK requires gov24-recovery.v1')
    return {'compatible':True,'required_protocol':required,'mode':'execution_state'}

def fetch_schema(base):
    url=urlsplit(base)
    if url.username or url.password or url.query or url.fragment:
        raise ValueError('Base URL must not contain credentials, query, or fragment')
    if url.scheme!='https' and not (url.scheme=='http' and url.hostname in {'localhost','127.0.0.1','::1'}):
        raise ValueError('Use HTTPS except for a local verification server')
    with urlopen(Request(base.rstrip('/')+'/openapi.json',headers={'Accept':'application/json'}),timeout=20) as response:
        data=response.read(8*1024*1024+1)
        if len(data)>8*1024*1024:raise ValueError('OpenAPI response is too large')
        return json.loads(data)

def install(apk, document, adb, serial=None, runner=subprocess.run, base_url=None):
    assessment=check_compatibility(apk,document,base_url)  # Must finish before any adb action.
    command=[adb]+(['-s',serial] if serial else [])+['install','-r','-t',str(apk)]
    result=runner(command,capture_output=True,text=True,timeout=120)
    if result.returncode or 'Success' not in result.stdout:raise RuntimeError('ADB installation did not succeed')
    return assessment

def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--serial')
    parser.add_argument('--adb',default=shutil.which('adb'))
    parser.add_argument('--check-only',action='store_true')
    args=parser.parse_args(argv)
    try:
        document=fetch_schema(args.base_url)
        if args.check_only:result=check_compatibility(args.apk,document,args.base_url)
        else:
            if not args.adb:raise ValueError('Supply --adb or add Android platform-tools to PATH')
            result=install(args.apk,document,args.adb,args.serial,base_url=args.base_url)
        print(json.dumps({**result,'installed':not args.check_only}))
        return 0
    except IncompatibleServer as blocked:
        print(json.dumps({'compatible':False,'installed':False,'reason':str(blocked)}))
        return 2
    except Exception as failed:
        print('Preflight failed; no further installation attempted: '+type(failed).__name__,file=sys.stderr)
        return 1

if __name__=='__main__':raise SystemExit(main())
