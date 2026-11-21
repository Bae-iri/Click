"""Failure-inclusive A-only paired completion-provider timing; no provider calls."""
import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
from statistics import mean, median
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from scripts.summarize_meta_controller_public_apps import (
    _v2_row_issues, _primary, _arm, _add_audited, _add_v2_timelines,
    VERSION2, MODE, REVISION2, SAFETY_COUNTERS)

POLICIES=('gemini','jev')


def number(value,signed=False):
    return type(value) in (int,float) and math.isfinite(value) and (signed or value>=0)


def distribution(values,*,signed=False):
    known=[v for v in values if number(v,signed)]
    return dict(observed=len(known),unknown=len(values)-len(known),
        minimum=min(known) if known else None,median=median(known) if known else None,
        maximum=max(known) if known else None,mean=round(mean(known),3) if known else None,
        observed_sum=round(sum(known),3) if known else None)


def schedule(manifest):
    if manifest.get('schema_version')!='click.a-completion-timing-manifest.v1' or manifest.get('budget_ms')!=30000:
        raise ValueError('Exact A-only timing manifest and 30000ms budget required')
    if type(manifest.get('extended')) is not bool:raise ValueError('Explicit extended flag required')
    base,extra=manifest.get('assignments'),manifest.get('conditional_assignments')
    if not isinstance(base,list) or len(base)!=24 or not isinstance(extra,list) or len(extra)!=12:
        raise ValueError('Freeze twenty-four primary and twelve conditional assignments')
    all_rows=base+extra;ids=[a.get('episode_id') for a in all_rows]
    if len(set(ids))!=36 or any(not isinstance(i,str) or not i for i in ids):raise ValueError('Unique frozen episode IDs required')
    tasks={a.get('task'):a.get('package') for a in base}
    if len(tasks)!=6 or len(set(tasks.values()))!=3 or any(list(tasks.values()).count(p)!=2 for p in set(tasks.values())):
        raise ValueError('Two goals for each of three apps required')
    expected_orders=[('r1','gemini'),('r1','jev'),('r2','jev'),('r2','gemini'),('r3','gemini'),('r3','jev')]
    if len({a.get('pair_id') for a in all_rows})!=36 or len({all_rows[i*6].get('block_id') for i in range(6)})!=6:
        raise ValueError('Unique pair and block identities required')
    task_order=[a.get('task') for a in base[:6]]
    for index,(repeat,policy) in enumerate(expected_orders):
        block=all_rows[index*6:(index+1)*6]
        if [a.get('task') for a in block]!=task_order or len({a.get('block_id') for a in block})!=1:
            raise ValueError('Frozen ABBA block/task order differs')
        for a in block:
            if (a.get('repeat')!=repeat or a.get('completion_policy')!=policy or a.get('arm')!='A'
                    or a.get('condition')!='normal' or a.get('package')!=tasks.get(a.get('task'))
                    or not isinstance(a.get('pair_id'),str) or not a['pair_id'] or not isinstance(a.get('block_id'),str) or not a['block_id']):
                raise ValueError('Unknown or changed frozen assignment')
    return (base+extra if manifest['extended'] else base),tasks


def bind_audit(rows,audit):
    issues=[];associations=audit.get('request_associations',[])
    if not isinstance(associations,list):return False,['missing_request_associations'],[]
    expected={};actual={}
    for row in rows:
        traces=row.get('request_traces');count=row.get('planner_requests');task=row.get('task_ref')
        if not isinstance(traces,list) or type(count) is not int or count<0 or len(traces)!=count:
            issues.append('invalid_episode_request_count');continue
        for trace in traces:
            ref=trace.get('request_ref')
            if not isinstance(ref,str) or not ref or ref in expected or trace.get('task_ref')!=task:
                issues.append('invalid_or_duplicate_client_reference');continue
            expected[ref]=(row['episode_id'],task,trace)
    for item in associations:
        ref=item.get('request_ref')
        if not isinstance(ref,str) or ref in actual:issues.append('duplicate_or_invalid_audit_reference');continue
        actual[ref]=item
        wanted=expected.get(ref)
        if wanted is None or (item.get('episode_id'),item.get('task_ref'))!=wanted[:2] or item.get('direct_checks_passed') is not True:
            issues.append('audit_episode_request_binding_mismatch')
        if wanted is not None:
            trace=wanted[2];state=trace.get('model_call_state');calls=trace.get('model_calls')
            known=state in {'observed','not_started'}
            valid_client=(state=='observed' and type(calls) is int and calls==1 or
                state=='not_started' and type(calls) is int and calls==0 or state=='unknown' and calls is None)
            if not valid_client:issues.append('invalid_client_model_measurement')
            if known and (state!=item.get('model_call_state') or calls!=item.get('model_calls')):
                issues.append('audit_client_model_count_mismatch')
            if state=='observed':
                wait,other=trace.get('model_wait_ms'),item.get('model_wait_ms')
                if not number(wait) or not number(other) or abs(wait-other)>.01:issues.append('audit_client_model_wait_mismatch')
            if trace.get('server_ref') and trace['server_ref']!=item.get('server_ref'):issues.append('audit_client_server_ref_mismatch')
            if trace.get('http_status') is not None and trace['http_status']!=item.get('http_status'):issues.append('audit_client_http_status_mismatch')
            for key in ('planning_mode','completion_provider','goal_assessment','input_tokens','output_tokens','thinking_tokens','total_tokens'):
                # A genuinely unknown client receipt may be resolved by the direct
                # server terminal. Known receipts must preserve presence and value.
                if known and ((key in trace)!=(key in item) or trace.get(key)!=item.get(key)):
                    issues.append('audit_client_metadata_mismatch_'+key)
                elif not known and key in trace and trace[key]!=item.get(key):
                    issues.append('audit_client_metadata_mismatch_'+key)
            if state=='observed' and any(key not in trace or key not in item for key in ('planning_mode','completion_provider')):
                issues.append('missing_observed_provider_metadata')
    if set(expected)!=set(actual):issues.append('audit_request_set_mismatch')
    if audit.get('direct_join_complete') is not True:issues.append('direct_join_incomplete')
    if audit.get('model_call_count_complete') is not True:issues.append('model_count_incomplete')
    if any(type(a.get('model_calls')) is not int or not (
            a.get('model_call_state')=='observed' and a['model_calls']==1 or
            a.get('model_call_state')=='not_started' and a['model_calls']==0) for a in associations):
        issues.append('unknown_or_invalid_model_count')
    return not issues,sorted(set(issues)),associations


def request_timings(rows,associations):
    ids={r['episode_id'] for r in rows};selected=[a for a in associations if a.get('episode_id') in ids]
    by_ref={a['request_ref']:a for a in selected};groups=defaultdict(list);missing=0
    for item in selected:
        state,provider,mode=item.get('model_call_state'),item.get('completion_provider'),item.get('planning_mode')
        if state=='not_started':category='provider_not_started'
        elif provider in POLICIES and mode in {'goal_first','next_action','result_only'}:
            if provider=='jev' and mode!='result_only':category='invalid_jev_navigation';missing+=1
            else:category=provider+('_completion' if mode=='result_only' else '_navigation')
        else:category='provider_or_mode_unknown';missing+=1
        groups[category].append(item)
    per_provider={}
    for category,items in sorted(groups.items()):
        per_provider[category]=dict(api_requests=len(items),observed_provider_attempts=sum(a.get('model_calls') or 0 for a in items),
            unknown_model_call_requests=sum(a.get('model_call_state')=='unknown' for a in items),
            model_wait_ms=distribution([a.get('model_wait_ms') for a in items if a.get('model_call_state')=='observed']),
            provider_errors=sum(a.get('provider_error') is True or a.get('service_failure_kind')=='provider' for a in items))
    first=[];queue=[];residual=[];frontend=[]
    for row in rows:
        traces=row.get('request_traces',[])
        if traces:first.append(traces[0].get('total_ms'))
        for trace in traces:
            frontend.append(trace.get('total_ms'));queue.append(trace.get('queue_ms'))
            joined=by_ref.get(trace.get('request_ref'),{})
            if joined.get('model_call_state')=='observed':
                work,wait=trace.get('work_ms'),joined.get('model_wait_ms')
                residual.append(work-wait if number(work) and number(wait) else None)
    return dict(actual_provider_requests=per_provider,provider_attribution_missing_requests=missing,
        first_api_total_ms=distribution(first),frontend_request_total_ms=distribution(frontend),
        frontend_queue_ms=distribution(queue),request_nonmodel_residual_ms=distribution(residual,signed=True),
        note='First API is not full first-guide latency; residual is not CPU. Provider errors/timeouts remain in wait distributions.')


def timelines(rows):
    joint=[];signed=[];frames=[];post=[];no_stable=0;unknown_actions=0
    for row in rows:
        if row.get('setup_valid') is not True or _v2_row_issues(row,30000):continue
        stable,verified=row.get('first_independent_stable_goal_ms'),row.get('first_runtime_verified_ms')
        if _primary(row,30000) is True:
            joint.append(row['ms_to_joint_success']-stable)
            signed.append(verified-stable)
        for event in row.get('completion_evidence_traces',[]):
            if event.get('stage')=='evidence' and event.get('reason')=='second_frame':frames.append(event.get('elapsed_ms'))
        if not number(stable):no_stable+=1;continue
        actions=row.get('native_action_trace')
        if not isinstance(actions,list):unknown_actions+=1;continue
        cutoff=row['ms_to_joint_success'] if _primary(row,30000) is True else 30000
        post.append(sum(a.get('native_accepted') is True and number(a.get('elapsed_ms')) and stable<a['elapsed_ms']<=cutoff for a in actions))
    return dict(successful_stable_to_joint_ms=distribution(joint),successful_stable_to_first_verified_ms=distribution(signed,signed=True),
        local_second_frame_wait_ms=distribution(frames),post_stable_native_action_count=sum(post) if post else None,
        post_stable_action_observed_episodes=len(post),post_stable_action_trace_unknown_episodes=unknown_actions,
        no_stable_goal_episodes=no_stable,setup_elapsed_ms=distribution([r.get('setup_elapsed_ms') for r in rows]),
        post_budget_provider_drain_ms=distribution([r.get('post_budget_provider_drain_ms') for r in rows]),
        note='Successful-only stage timings exclude failures; signed lag may be negative because independent oracle requires700ms versus native350ms.')


def summarize(rows,manifest,request_audit):
    assignments,tasks=schedule(manifest);frozen={a['episode_id']:a for a in assignments};seen=set();issues=[];timing_issues=[];grouped=defaultdict(dict)
    for row in rows:
        episode=row.get('episode_id');a=frozen.get(episode)
        if not a or episode in seen:raise ValueError('Unassigned or duplicate measured episode')
        seen.add(episode)
        if row.get('schema_version')!=VERSION2 or row.get('evidence_mode')!=MODE:raise ValueError('Actual native public-app rows required')
        if any(row.get(k)!=a[k] for k in ('pair_id','task','package','arm','condition')) or row.get('arm_order')!=0:
            raise ValueError('Measured assignment differs from the frozen schedule')
        if row.get('coordinator_policy')!='current':issues.append('missing_or_wrong_current_policy')
        if 'completion_policy' in row and row['completion_policy']!=a['completion_policy']:issues.append('episode_completion_policy_mismatch')
        if row.get('measurement_revision')!=REVISION2:issues.append('measurement_revision_mismatch')
        if row.get('budget_ms')!=30000:issues.append('episode_budget_mismatch')
        if row.get('setup_valid') is not True:issues.append('invalid_setup')
        if row.get('oracle_status')!='known' or _primary(row,30000) is None:issues.append('unknown_primary_outcome')
        if row.get('settings_guard_observable') is not True or row.get('cleanup_oracle_status')!='known':issues.append('unknown_native_guard_or_cleanup')
        if row.get('pending_gateway_callback_at_end') is not False or row.get('pending_native_actions_at_end')!=0:issues.append('pending_work_after_drain')
        found=_v2_row_issues(row,30000)
        if found:timing_issues.append(dict(episode_id=episode,issues=found));issues.append('invalid_observation_window')
        grouped[(a['repeat'],a['task'])][a['completion_policy']]=row
    if [r['episode_id'] for r in rows]!=[a['episode_id'] for a in assignments[:len(rows)]]:issues.append('execution_order_mismatch')
    contexts={r.get('measurement_context_key') for r in rows}
    if contexts and (None in contexts or '' in contexts or len(contexts)!=1 or manifest.get('measurement_context_key') not in (None,next(iter(contexts)))):
        issues.append('measurement_context_mismatch')
    for pair in grouped.values():
        if len(pair)==2 and (not pair['gemini'].get('initial_state_key') or pair['gemini'].get('initial_state_key')!=pair['jev'].get('initial_state_key')):issues.append('paired_start_mismatch')
    preparation=manifest.get('preparation')
    if preparation is not None and (not isinstance(preparation,dict) or preparation.get('passed') is not True):issues.append('preparation_not_passed')
    audit_ok,audit_issues,associated=bind_audit(rows,request_audit);issues+=audit_issues
    for item in associated:
        assignment=frozen.get(item.get('episode_id'),{})
        if assignment.get('completion_policy')=='gemini' and item.get('completion_provider')=='jev':issues.append('runtime_completion_policy_mismatch')
    attribution=request_timings(rows,associated);attribution['audit_binding_verified']=audit_ok
    if attribution['provider_attribution_missing_requests']:issues.append('unknown_provider_attribution')
    by_policy={p:[r for r in rows if frozen[r['episode_id']]['completion_policy']==p] for p in POLICIES}
    def stats(selected,scheduled):
        result=_arm(selected,30000,scheduled);_add_audited(result,selected,request_audit,audit_ok,VERSION2);_add_v2_timelines(result,selected,30000)
        result['time_breakdown']=timelines(selected);result['requests']=request_timings(selected,associated);return result
    repetitions=3 if manifest['extended'] else 2
    policies={p:stats(by_policy[p],6*repetitions) for p in POLICIES}
    packages={pkg:{p:stats([r for r in by_policy[p] if r['package']==pkg],2*repetitions) for p in POLICIES} for pkg in sorted(set(tasks.values()))}
    goals={task:{p:stats([r for r in by_policy[p] if r['task']==task],repetitions) for p in POLICIES} for task in tasks}
    blocks={block:stats([r for r in rows if frozen[r['episode_id']]['block_id']==block],6) for block in dict.fromkeys(a['block_id'] for a in assignments)}
    matched=[]
    def exposed(row,provider):return any(a.get('episode_id')==row['episode_id'] and a.get('planning_mode')=='result_only' and a.get('completion_provider')==provider and a.get('model_call_state')=='observed' for a in associated)
    for (repeat,task),pair in sorted(grouped.items()):
        if set(pair)==set(POLICIES) and all(r.get('setup_valid') is True for r in pair.values()) and pair['gemini'].get('initial_state_key')==pair['jev'].get('initial_state_key'):
            matched.append(dict(repeat=repeat,task=task,both_actual_completion_providers_exposed=(exposed(pair['gemini'],'gemini') and exposed(pair['jev'],'jev')) if audit_ok else None,
                gemini_joint=_primary(pair['gemini'],30000),jev_joint=_primary(pair['jev'],30000)))
    safe=not any(r.get(k,0) for r in rows for k in SAFETY_COUNTERS);complete=len(rows)==len(assignments);valid=not issues
    hard=complete and valid and safe and audit_ok
    g,j=(policies[p] for p in POLICIES);regressions=[pkg for pkg,values in packages.items() if values['jev']['primary_successes']<values['gemini']['primary_successes']]
    nonregression=j['primary_successes']>=g['primary_successes'] and not regressions
    delta_time=j['mean_budget_penalized_ms']-g['mean_budget_penalized_ms'] if all(number(x['mean_budget_penalized_ms']) for x in (g,j)) else None
    delta_calls=j['audited_model_calls_lower_bound']-g['audited_model_calls_lower_bound'] if audit_ok else None
    exposed_pairs=sum(x['both_actual_completion_providers_exposed'] is True for x in matched) if audit_ok else None
    exposure_sufficient=audit_ok and exposed_pairs>=4
    eligible=hard and nonregression and exposure_sufficient and delta_time<=0 and delta_calls<=0 and delta_time<0
    return dict(schema_version='click.a-completion-timing-summary.v1',scheduled_episodes=len(assignments),observed_episodes=len(rows),
        unexecuted_episodes=len(assignments)-len(rows),conditional_not_run_episodes=0 if manifest['extended'] else 12,extended=manifest['extended'],
        matrix_complete=complete,measurement_valid=valid,measurement_issues=sorted(set(issues)),observation_issues=timing_issues,
        direct_request_join_complete=audit_ok,safety_verdict='NO_SAFETY_EVENT_OBSERVED' if safe else 'STOP_SAFETY_EVENT',
        policies=policies,packages=packages,goals=goals,blocks=blocks,paired_outcomes=matched,matched_actual_completion_provider_pairs=exposed_pairs,
        requests=attribution,comparison_gate=dict(hard_gates_passed=hard,no_overall_or_app_success_regression=nonregression,app_primary_regressions=regressions,
            api_attempt_delta_jev_minus_gemini=delta_calls,mean_penalized_delta_jev_minus_gemini_ms=round(delta_time,3) if delta_time is not None else None,
            actual_completion_exposure_sufficient=exposure_sufficient,eligible_for_next_narrow_validation=eligible),
        conditional_extension_eligible=not manifest['extended'] and hard and nonregression and exposed_pairs<4,
        next_validation_stage='GO_TO_REPEATED_NARROW_COMPLETION_VALIDATION' if eligible else 'NO_GO_OR_INCONCLUSIVE',
        default_enablement_verdict='NO_GO_SMALL_SAMPLE_SINGLE_DEVICE',
        notes=['A/CURRENT only. Initial six exploratory Gemini episodes are separate.',
            'Assigned failures/provider errors remain; unknown/missing measurements are not known failures.',
            'Actual-exposure subsets and successful-only stages are descriptive, not causal superiority.',
            'Frontend requests, actual provider attempts and observed token subtotals are not bills.'])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('episodes','manifest','request-audit','output'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();rows=[json.loads(line) for line in args.episodes.read_text().splitlines() if line.strip()]
    result=summarize(rows,json.loads(args.manifest.read_text()),json.loads(args.request_audit.read_text()))
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:result[k] for k in ('scheduled_episodes','observed_episodes','matrix_complete','measurement_valid','matched_actual_completion_provider_pairs','next_validation_stage')}))


if __name__=='__main__':main()
