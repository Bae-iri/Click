"""Direct request/task joins for real public Android apps, including HTTP rejects.

An HTTP rejection after service entry may still contain a provider attempt. A
client timeout can leave its receipt unknown while the server knows the call.
Never equate either case with confirmed zero model work.
"""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re


def _ref(value):
    return isinstance(value,str) and re.fullmatch(r'[0-9a-f]{24}',value) is not None


def _wait(value):
    return type(value) in (int,float) and math.isfinite(value) and 0<=value<=120000


def _calls(value,wanted):
    return type(value) is int and value==wanted


EPISODE_SCHEMAS=('click.native-public-apps.v1',)
CONTEXT_BOOLEANS=('recovery_context_used','capabilities_applied','allowed_actions_context_used')
CONTEXT_COUNTS={'allowed_action_count':322,'recovery_blocked_action_count':16}


def _episode_schema(episodes,explicit=None):
    versions={row.get('schema_version') for row in episodes}
    if len(versions)>1:raise ValueError('Mixed native public apps episode versions')
    version=next(iter(versions),explicit or EPISODE_SCHEMAS[0])
    if version not in EPISODE_SCHEMAS or explicit is not None and explicit!=version:
        raise ValueError('Actual native public apps episode schema required')
    return version


def _context_metadata(record):
    copied={};invalid=[]
    for key in CONTEXT_BOOLEANS:
        if key in record:
            if type(record[key]) is bool:copied[key]=record[key]
            else:invalid.append(key)
    for key,limit in CONTEXT_COUNTS.items():
        if key in record:
            if type(record[key]) is int and 0<=record[key]<=limit:copied[key]=record[key]
            else:invalid.append(key)
    if 'recovery_attempt' in record:
        if copied.get('recovery_context_used') is True and type(record['recovery_attempt']) is int and 1<=record['recovery_attempt']<=3:
            copied['recovery_attempt']=record['recovery_attempt']
        else:invalid.append('recovery_attempt')
    if 'recovery_policy' in record or 'recovery_reason' in record:
        policy,reason=record.get('recovery_policy'),record.get('recovery_reason')
        attempt=copied.get('recovery_attempt')
        if (copied.get('recovery_context_used') is True and policy in {'mc_v0','mc_v1'}
                and reason in {'accepted_action_unconfirmed','observed_transition_unconfirmed'}
                and type(attempt) is int and 1<=attempt<=(2 if reason=='observed_transition_unconfirmed' else 3)
                and (reason!='observed_transition_unconfirmed' or policy=='mc_v1')):
            copied.update(recovery_policy=policy,recovery_reason=reason)
        else:invalid.append('recovery_policy_or_reason')
    for key in ('input_tokens','output_tokens','thinking_tokens','total_tokens'):
        if key in record:
            value=record[key]
            if record.get('model_call_state')=='observed' and type(value) is int and 0<=value<=1000000:copied[key]=value
            else:invalid.append(key)
    return copied,invalid


def audit(episodes,terminal,*,episode_schema=None):
    version=_episode_schema(episodes,episode_schema)
    if any(row.get('evidence_mode')!='live_native_public_apps' for row in episodes):
        raise ValueError('Actual native public apps episodes required')
    expected,actual,issues={}, {}, []
    for row in episodes:
        traces=row.get('request_traces')
        if not isinstance(traces,list):
            issues.append('missing_client_request_traces');continue
        if len(traces)!=row.get('planner_requests'):issues.append('planner_trace_count_mismatch')
        reported=0
        for trace in traces:
            key=trace.get('request_ref')
            if not _ref(key) or not _ref(trace.get('task_ref')):
                issues.append('invalid_client_reference');continue
            if key in expected:issues.append('duplicate_client_request_ref')
            if trace.get('task_ref')!=row.get('task_ref'):issues.append('client_task_ref_mismatch')
            expected[key]=(row,trace)
            state,calls=trace.get('model_call_state'),trace.get('model_calls')
            if state=='observed' and _calls(calls,1):reported+=1
            elif state=='not_started' and _calls(calls,0):pass
            elif state=='unknown' and calls is None:pass
            else:issues.append('invalid_client_model_measurement')
        if reported!=row.get('actual_model_calls',0):issues.append('client_observed_call_count_mismatch')
    servers=set()
    for record in terminal:
        if (record.get('schema_version')!='local-meta-controller-public-apps-facade.v1'
                or record.get('evidence_mode')!='live_native_public_apps' or record.get('event')!='request_terminal'):
            issues.append('invalid_native_terminal_schema')
        server,key=record.get('server_ref'),record.get('request_ref')
        if not _ref(server) or server in servers:issues.append('missing_or_duplicate_server_ref')
        servers.add(server)
        if not _ref(key):issues.append('unidentified_terminal_record');continue
        if key in actual:issues.append('duplicate_server_request_ref')
        actual[key]=record
    missing,orphan=sorted(set(expected)-set(actual)),sorted(set(actual)-set(expected))
    if missing:issues.append('missing_terminal_records')
    if orphan:issues.append('orphan_terminal_records')
    unknown=set(missing);known_calls=0;known_wait=0;timing_unknown=False;associations=[];context_invalid=[]
    for key,record in actual.items():
        state,calls,wait=record.get('model_call_state'),record.get('model_calls'),record.get('model_wait_ms')
        if state=='observed' and _calls(calls,1):
            known_calls+=1
            if _wait(wait):known_wait+=wait
            else:timing_unknown=True
        elif state=='not_started' and _calls(calls,0):pass
        elif state=='unknown' and calls is None:unknown.add(key)
        else:issues.append('invalid_server_model_measurement');unknown.add(key)
        if key not in expected:continue
        row,trace=expected[key];problems=[]
        if record.get('task_ref')!=trace.get('task_ref'):problems.append('request_task_ref_mismatch')
        if trace.get('server_ref') and trace['server_ref']!=record.get('server_ref'):problems.append('server_ref_mismatch')
        if trace.get('http_status') is not None and trace['http_status']!=record.get('http_status'):problems.append('http_status_mismatch')
        if trace.get('model_call_state')=='observed':
            if state!='observed' or not _calls(trace.get('model_calls'),1):problems.append('observed_call_mismatch')
            if not _wait(trace.get('model_wait_ms')) or not _wait(wait) or abs(trace['model_wait_ms']-wait)>.01:problems.append('observed_wait_mismatch')
        elif trace.get('model_call_state')=='not_started' and state!='not_started':problems.append('confirmed_zero_mismatch')
        # Client unknown can be resolved by an exactly joined server record;
        # it is not a mismatch merely because a connection was lost.
        issues.extend(problems)
        association=dict(episode_id=row.get('episode_id'),pair_id=row.get('pair_id'),arm=row.get('arm'),
            condition=row.get('condition'),
            request_ref=key,task_ref=trace.get('task_ref'),server_ref=record.get('server_ref'),
            http_status=record.get('http_status'),error_code=record.get('error_code'),model_call_state=state,
            model_calls=calls,model_wait_ms=wait,service_state=record.get('service_state'),
            service_failure_kind=record.get('service_failure_kind'),service_failure_stage=record.get('service_failure_stage'),
            service_reason_code=record.get('service_reason_code'),response_delivery=record.get('response_delivery'),
            provider_error=record.get('provider_error') is True,
            plan_or_parse_error=record.get('plan_or_parse_error') is True,
            direct_checks_passed=not problems)
        if version==EPISODE_SCHEMAS[0]:
            metadata,invalid=_context_metadata(record);association.update(metadata)
            context_invalid.extend(dict(request_ref=key,field=field) for field in invalid)
        associations.append(association)
    joined=not issues;complete=joined and not unknown
    status=('DIRECT_JOIN_RECONCILED' if complete and not timing_unknown else
        'DIRECT_JOIN_COMPLETE_TIMING_UNKNOWN' if complete else
        'DIRECT_JOIN_COMPLETE_MODEL_COUNTS_UNKNOWN' if joined else 'INDETERMINATE_OR_MISMATCHED')
    result=dict(schema_version='click.native-public-apps-provider-audit.'+version.rsplit('.',1)[1],evidence_mode='live_native_public_apps',
        status=status,direct_request_id_join_available=True,direct_join_complete=joined,
        model_call_count_complete=complete,model_wait_measurement_complete=complete and not timing_unknown,
        episodes=len(episodes),planner_requests=sum(row.get('planner_requests',0) for row in episodes),
        client_request_traces=len(expected),terminal_records=len(terminal),matched_request_records=len(associations),
        provider_model_calls=known_calls,provider_model_wait_ms=round(known_wait,3),unknown_model_call_requests=len(unknown),
        missing_request_refs=missing,orphan_request_refs=orphan,
        provider_errors=sum(record.get('provider_error') is True for record in terminal),
        plan_or_parse_errors=sum(record.get('plan_or_parse_error') is True for record in terminal),
        blocked_navigation_proposals=sum(record.get('error_code')=='unsafe_navigation_proposal' for record in terminal),
        http_status_counts=dict(Counter(str(record.get('http_status')) for record in terminal)),
        request_associations=associations,issues=sorted(set(issues)),
        note='Direct joining establishes transport accounting, not goal success or default deployment. Classified failures stay in performance outcomes; unknown calls are a lower bound.')
    if version==EPISODE_SCHEMAS[0]:
        result.update(episode_schema=version,context_diagnostic_invalid_fields=context_invalid,
            context_diagnostic_scope='Optional typed payload-boundary metadata; forwarding requires joined observed model call. Missing metadata does not invalidate transport accounting.')
    return result


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--episodes',type=Path,required=True)
    parser.add_argument('--terminal','--provider',dest='terminal',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--episode-schema',choices=EPISODE_SCHEMAS)
    args=parser.parse_args(argv)
    result=audit([json.loads(x) for x in args.episodes.read_text().splitlines() if x.strip()],
        [json.loads(x) for x in args.terminal.read_text().splitlines() if x.strip()],episode_schema=args.episode_schema)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({key:result[key] for key in ('status','episodes','terminal_records','provider_model_calls','unknown_model_call_requests','issues')}))


if __name__=='__main__':main()
