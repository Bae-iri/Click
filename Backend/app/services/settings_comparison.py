"""Descriptive A/B results. No winner is inferred from provider outages or unmatched trials."""
from collections import defaultdict
from hashlib import sha256
import json
from pathlib import Path
from statistics import median


def trial_summaries(records):
    grouped=defaultdict(list)
    for record in records:
        grouped[record['run_ref']].append(record)
    trials=[]
    for run,items in grouped.items():
        items=sorted(items,key=lambda r:(r['at'],r.get('trace_turn',r.get('step',0))))
        first,last=items[0],items[-1]
        success=next((r for r in items if r['verification']['final']['status']=='SUCCESS'),None)
        attempts={}
        for r in items:
            key=r.get('attempt_ref')
            if not key:continue
            statuses=attempts.setdefault(key,{'action':'UNKNOWN','subgoal':'UNKNOWN'})
            for level in statuses:
                value=r['verification'][level]['status']
                if statuses[level]!='SUCCESS':statuses[level]=value
        provider_errors=[r for r in items if r.get('planner',{}).get('failure_kind')=='provider'
            or r.get('jev',{}).get('called') and r.get('jev',{}).get('status') in {'provider_error','network_error','timeout'}]
        native=first.get('native_effect') or {}
        condition={k:first.get(k) for k in ('scope_hash','base_graph_hash','runtime_code_hash','jev_model')}
        condition['planner_model'] = first.get('planner_model')
        condition.update(start_state=(first.get('observation') or {}).get('state_id'),
            initial_system_value=native.get('current_system_value'),
            initial_control_value=native.get('current_control_value'))
        complete=bool(first.get('first_observation') and first.get('trace_turn')==1
            and all(condition[k] is not None for k in ('scope_hash','base_graph_hash','runtime_code_hash','jev_model','planner_model','start_state'))
            and condition['initial_system_value'] is not None)
        condition_hash=sha256(json.dumps(condition,sort_keys=True).encode()).hexdigest()[:16] if complete else None
        trials.append(dict(run_ref=run,arm=last['arm'],goal_type=last['goal_type'],condition=condition,
            plan_sources=sorted({r.get('plan_source') for r in items if r.get('plan_source')}),
            condition_hash=condition_hash,complete_comparison_context=complete,native_success=bool(success),
            final_status='SUCCESS' if success else last['verification']['final']['status'],
            user_result=last.get('latest_user_result'),provider_unavailable=bool(provider_errors),
            invalid_plan_attempts=sum(r.get('planner',{}).get('failure_kind')=='plan' for r in items),
            action_attempts=len(attempts),action_successes=sum(a['action']=='SUCCESS' for a in attempts.values()),
            action_unknown=sum(a['action']=='UNKNOWN' for a in attempts.values()),
            action_waiting=sum(a['action']=='WAITING' for a in attempts.values()),
            subgoal_successes=sum(a['subgoal']=='SUCCESS' for a in attempts.values()),
            guidance_count=(len({r['guide_ref'] for r in items if r.get('guide_ref')
                and r.get('command_type') in {'highlight','confirmation'}})
                if any(r.get('guide_ref') for r in items) else None),
            verified_failures=max(r.get('total_failed_attempts',0) for r in items),
            planner_calls=max(r.get('planner_calls',0) for r in items),
            planner_http_attempts=max(r.get('planner_http_attempts',0) for r in items),
            jev_calls=sum(bool(r.get('jev',{}).get('called')) for r in items),
            planner_tokens=sum(r.get('planner',{}).get('input_tokens',0)+r.get('planner',{}).get('output_tokens',0)
                +r.get('planner',{}).get('thinking_tokens',0) for r in items),
            model_wait_ms=sum((r.get('planner',{}).get('latency_ms',0) if r.get('planner',{}).get('called') else 0)
                +(r.get('jev',{}).get('provider_latency_ms',0) if r.get('jev',{}).get('called') else 0) for r in items),
            turn_processing_ms=sum(r.get('turn_latency_ms',0) for r in items),
            completion_observed_ms=max(0,success['at']-first['at'])*1000 if success else None,
            observed_wall_ms=max(0,last['at']-first['at'])*1000))
    return trials


def metrics(trials):
    n=len(trials)
    return dict(trials=n,native_goal_successes=sum(t['native_success'] for t in trials),
        native_success_rate=sum(t['native_success'] for t in trials)/n if n else None,
        unknown_or_incomplete=sum(t['final_status'] in {'UNKNOWN','WAITING'} for t in trials),
        blocked=sum(t['final_status']=='BLOCKED' for t in trials),
        failed=sum(t['final_status']=='FAILED' for t in trials),
        invalid_plan_attempts=sum(t['invalid_plan_attempts'] for t in trials),
        provider_unavailable_trials=sum(t['provider_unavailable'] for t in trials),
        learned_route_trials=sum('learned_route' in t.get('plan_sources',()) for t in trials),
        approved_graph_fallback_trials=sum('approved_graph_fallback' in t.get('plan_sources',()) for t in trials),
        user_confirmed_achieved=sum(t['user_result']=='ACHIEVED' for t in trials),
        user_stopped=sum(t['user_result']=='USER_STOPPED' for t in trials),
        action_attempts=sum(t['action_attempts'] for t in trials),
        action_successes=sum(t['action_successes'] for t in trials),
        action_unknown=sum(t['action_unknown'] for t in trials),
        action_waiting=sum(t['action_waiting'] for t in trials),
        subgoal_successes=sum(t['subgoal_successes'] for t in trials),
        verified_failures=sum(t['verified_failures'] for t in trials),
        planner_calls=sum(t['planner_calls'] for t in trials),jev_calls=sum(t['jev_calls'] for t in trials),
        planner_http_attempts=sum(t['planner_http_attempts'] for t in trials),
        planner_tokens=sum(t['planner_tokens'] for t in trials),
        median_model_wait_ms=median([t['model_wait_ms'] for t in trials]) if n else None,
        median_guidance_count=median([t['guidance_count'] for t in trials if t['guidance_count'] is not None])
            if any(t['guidance_count'] is not None for t in trials) else None,
        median_turn_processing_ms=median([t['turn_processing_ms'] for t in trials]) if n else None,
        median_completion_observed_ms=median([t['completion_observed_ms'] for t in trials if t['native_success']])
            if any(t['native_success'] for t in trials) else None)


def compare(records):
    trials=trial_summaries(records)
    goals=defaultdict(list)
    for trial in trials:goals[trial['goal_type']].append(trial)
    groups=[]
    for goal,items in sorted(goals.items()):
        conditions=defaultdict(list)
        for item in items:
            if item['complete_comparison_context']:conditions[item['condition_hash']].append(item)
        matched=[]
        for condition,values in sorted(conditions.items()):
            arms={arm:[t for t in values if t['arm']==arm] for arm in ('graph','llm')}
            if not all(arms.values()):continue
            graph,llm=metrics(arms['graph']),metrics(arms['llm'])
            matched.append(dict(condition_hash=condition,condition=values[0]['condition'],graph=graph,llm=llm,
                provider_available_only={arm:metrics([t for t in arms[arm] if not t['provider_unavailable']])
                    for arm in ('graph','llm')},
                llm_minus_graph=dict(native_success_rate=llm['native_success_rate']-graph['native_success_rate'],
                    median_model_wait_ms=llm['median_model_wait_ms']-graph['median_model_wait_ms']),
                interpretation='DESCRIPTIVE_ONLY'))
        groups.append(dict(goal_type=goal,all_trials={arm:metrics([t for t in items if t['arm']==arm]) for arm in ('graph','llm')},
            matched_conditions=matched,missing_comparison_context=sum(not t['complete_comparison_context'] for t in items),
            status='DESCRIPTIVE_ONLY' if matched else 'NEEDS_MATCHED_TRIALS'))
    return dict(schema_version='settings-ab-comparison.v1',groups=groups,
        recommendation='NOT_DETERMINED',learned_routes_applied=False,
        limitations=['No independent gold or statistical superiority claim.',
            'Manufacturer/OS/locale/client-version scope is not a device model or Settings app version.',
            'Observed completion time starts at the first runtime event; preparation time is excluded.',
            'Planner tokens only; monetary cost and Jev token usage are not measured.',
            'With route reuse enabled, llm-arm trials may use AI2 routes or the approved-graph fallback; '
            'see learned_route_trials and approved_graph_fallback_trials before reading llm results.'])


def write_report(report, output_dir):
    """Export one JSON report and a readable table without keys or screen content."""
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    (output / 'comparison.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    mode = report.get('evidence_mode', 'device_runtime_logs')
    synthetic = 'replay' in mode
    lines = ['# 설정 그래프와 하위 목표 플래너 비교', '', f'근거: `{mode}`', '']
    models = {report['planner_model']} if report.get('planner_model') else {
        item['condition']['planner_model'] for group in report['groups'] for item in group['matched_conditions']
        if item['condition'].get('planner_model')}
    lines += ['하위 목표 플래너 모델 ' + (', '.join(f'`{model}`' for model in sorted(models)) if models else '기록 없음'), '']
    if synthetic:
        lines += ['**화면·조작·설정값은 모의 데이터입니다. 실기기 성공률이나 LLM 도입 효과로 해석하지 마세요.**', '']
    lines += ['| 목표 | 방식 | 시험 | 검증 성공 | 미완료 | API 장애 시험 | 잘못된 계획 | 모델 대기 중앙값(ms) | 계획 토큰 |',
        '|---|---|---:|---:|---:|---:|---:|---:|---:|']
    for group in report['groups']:
        for arm in ('graph', 'llm'):
            value = group['all_trials'][arm]
            wait = value['median_model_wait_ms']
            lines.append(f"| {group['goal_type']} | {arm} | {value['trials']} | {value['native_goal_successes']} | "
                f"{value['unknown_or_incomplete']} | {value['provider_unavailable_trials']} | "
                f"{value['invalid_plan_attempts']} | {round(wait) if wait is not None else '-'} | {value['planner_tokens']} |")
    if not report['groups']:lines += ['', '아직 비교할 시험 기록이 없습니다. 두 앱에서 같은 목표를 실행한 뒤 다시 내보내세요.']
    lines += ['', '| 목표 | 같은 조건의 비교 그룹 | 조건 정보가 부족한 시험 |', '|---|---:|---:|']
    for group in report['groups']:
        lines.append(f"| {group['goal_type']} | {len(group['matched_conditions'])} | {group['missing_comparison_context']} |")
    lines += ['', 'API 장애를 포함한 전체 결과와, 장애가 없었던 시험 결과는 JSON에서 별도로 확인할 수 있습니다.',
        '시작값·시작 화면·실행 코드·모델 설정이 다른 시험은 같은 조건으로 묶지 않습니다.',
        '결론: 아직 자동 도입 권고를 내리지 않습니다. 실기기에서 조건을 맞춘 반복 시험으로 성공률·지연·추가 비용을 비교하세요.', '']
    (output / 'comparison.md').write_text('\n'.join(lines), encoding='utf-8')
