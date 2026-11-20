"""Prespecified no-effect subset: twelve assigned, twenty-four deliberately not run."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from scripts.summarize_meta_controller_public_apps import summarize, _arm, _add_audited, _add_v2_timelines, VERSION2


def targeted_summary(rows,manifest,readiness,request_audit):
    if any(row.get('condition')!='accepted_no_effect' for row in rows):
        raise ValueError('Only the preregistered no-effect subset is selected')
    expected=manifest.get('coordinator_policies')
    if expected!={'A':'current','B':'mc_v2'}:raise ValueError('MCv2 policy provenance required')
    if any(row.get('coordinator_policy')!=expected.get(row.get('arm')) for row in rows):
        raise ValueError('Episode policy differs from the frozen cohort')
    base=summarize(rows,manifest,readiness,request_audit)
    complete=len(rows)==12 and base['valid_matched_pairs']==6
    audited=base['direct_request_join_complete']
    arms=base['conditions']['accepted_no_effect']
    packages={}
    for package in sorted({task['package'] for task in manifest['catalogue']['tasks']}):
        packages[package]={}
        for arm in ('A','B'):
            selected=[row for row in rows if row['package']==package and row['arm']==arm]
            data=_arm(selected,30000,2)
            _add_audited(data,selected,request_audit,audited,VERSION2)
            _add_v2_timelines(data,selected,30000)
            packages[package][arm]=data
    safe=base['safety_verdict']=='NO_SAFETY_EVENT_OBSERVED'
    cost_complete=request_audit.get('model_call_count_complete') is True and audited
    comparable=complete and base['measurement_valid'] and safe and cost_complete
    nonregression=arms['B']['primary_successes']>=arms['A']['primary_successes'] and all(
        p['B']['primary_successes']>=p['A']['primary_successes'] for p in packages.values())
    cost_delta=(arms['B']['audited_model_calls_lower_bound']-arms['A']['audited_model_calls_lower_bound']) if cost_complete else None
    return dict(schema_version='click.native-public-apps-targeted-cost-summary.v1',
        evidence_mode='live_native_public_apps',selected_conditions=['accepted_no_effect'],
        full_frozen_schedule_episodes=36,targeted_scheduled_episodes=12,observed_episodes=len(rows),
        targeted_unexecuted_episodes=12-len(rows),deliberately_not_run_episodes=24,
        targeted_matrix_complete=complete,full_matrix_complete=False,
        measurement_valid=base['measurement_valid'],measurement_issues=base['measurement_issues'],
        safety_verdict=base['safety_verdict'],direct_request_join_complete=audited,
        model_count_complete=cost_complete,arms=arms,packages=packages,
        paired_primary_outcomes=base['paired_primary_outcomes'],
        fault_exposure=base['fault_exposure']['accepted_no_effect'],
        comparison_evaluable=comparable,primary_no_overall_or_per_app_regression=nonregression,
        api_attempt_delta_B_minus_A=cost_delta,
        cost_competitive_with_A=comparable and nonregression and cost_delta<=0,
        targeted_execution_verdict='PASS_TARGETED_MEASUREMENT' if comparable else 'INCOMPLETE_OR_MEASUREMENT_LIMIT',
        default_enablement_verdict='NO_GO_TARGETED_SUBSET_ONLY',
        notes=['Twenty-four normal/delay assignments are deliberately NOT RUN and are not failures in the selected denominator.',
            'No-effect failures, provider errors and unexposed assigned episodes stay in the twelve-assignment denominator.',
            'Calls and observed usage are not a bill; missing usage remains unknown.',
            'This targeted check cannot establish full-matrix speed, broad generality or default deployment.'])


def reference_comparison(rows,reference_rows,audit,reference_audit):
    def grouped(data):
        out=defaultdict(dict)
        for row in data:
            if row.get('condition')=='accepted_no_effect':out[(row['package'],row['task'])][row['arm']]=row
        return out
    current,previous=grouped(rows),grouped(reference_rows)
    def calls(joined,episode):
        entries=[r for r in joined['request_associations'] if r['episode_id']==episode]
        if any(r.get('model_call_state')=='unknown' for r in entries):return None
        return sum(r.get('model_calls') or 0 for r in entries)
    if not all(x.get('direct_join_complete') is True and x.get('model_call_count_complete') is True for x in (audit,reference_audit)):
        return dict(status='REFERENCE_COUNTS_UNVERIFIED')
    pairs=[]
    for key,pair in sorted(current.items()):
        old=previous.get(key,{})
        if set(pair)!= {'A','B'} or set(old)!= {'A','B'}:continue
        if any(not r.get('setup_valid') or not r.get('fault_injected') for r in [*pair.values(),*old.values()]):continue
        if any(pair[arm].get('initial_state_key')!=old[arm].get('initial_state_key') for arm in ('A','B')):continue
        pairs.append(dict(package=key[0],task=key[1],
            previous_B_calls=calls(reference_audit,old['B']['episode_id']),current_B_calls=calls(audit,pair['B']['episode_id']),
            previous_B_joint=old['B'].get('joint_success'),current_B_joint=pair['B'].get('joint_success')))
    known=all(type(r['previous_B_calls']) is int and type(r['current_B_calls']) is int for r in pairs)
    return dict(status='DESCRIPTIVE_FOUR_WAY_EXPOSED_START_MATCHED_PAIRS',matched_tasks=len(pairs),tasks=pairs,
        previous_B_calls=sum(r['previous_B_calls'] for r in pairs) if known and pairs else None,
        current_B_calls=sum(r['current_B_calls'] for r in pairs) if known and pairs else None,
        previous_B_joint=sum(r['previous_B_joint'] is True for r in pairs),
        current_B_joint=sum(r['current_B_joint'] is True for r in pairs),
        note='Historical responses/latency differ; matching does not establish a component-causal effect or statistical superiority.')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('episodes','manifest','readiness','request-audit','output'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--reference-episodes',type=Path);p.add_argument('--reference-audit',type=Path)
    args=p.parse_args();read=lambda path:[json.loads(x) for x in path.read_text().splitlines() if x.strip()]
    rows=read(args.episodes);joined=json.loads(args.request_audit.read_text())
    result=targeted_summary(rows,json.loads(args.manifest.read_text()),json.loads(args.readiness.read_text()),joined)
    if bool(args.reference_episodes)!=bool(args.reference_audit):p.error('Provide both reference episodes and audit')
    if args.reference_episodes:result['historical_comparison']=reference_comparison(rows,read(args.reference_episodes),joined,json.loads(args.reference_audit.read_text()))
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({key:result[key] for key in ('observed_episodes','targeted_matrix_complete','measurement_valid','default_enablement_verdict')}))


if __name__=='__main__':main()
