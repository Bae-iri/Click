"""Preregistered MetaController trials and provenance-aware episode analysis.

This module never infers task success from the controller's own verdict and never
turns scripted driver/conformance measurements into production-app results.
"""
from collections import defaultdict
from hashlib import sha256
from math import ceil, isfinite
from random import Random
from statistics import mean, median


VERSION = 'click-meta-controller-experiment.v1'
ARMS = ('A', 'B', 'C', 'D')
SOURCES = ('real_device_scripted_driver', 'controlled_contract', 'live_synthetic_replay',
           'real_device_product')
SETTINGS_TASKS = tuple(f'settings.{setting}.{direction}'
    for setting in ('font_size', 'brightness', 'volume')
    for direction in ('increase', 'decrease'))
MATCH_FIELDS = ('source', 'task_id', 'app_id', 'condition', 'budget_ms',
                'planner_arm', 'driver_mode', 'initial_state_hash',
                'intervention_seed', 'device_scope', 'model_version', 'base_code_revision')


def protocol(*, seed=20261120, app_tasks=None, include_ablation=False):
    """Return scheduled trials only. Unassigned app tasks are explicitly NOT_READY."""
    if app_tasks is None:
        app_tasks = [dict(task_id=f'multi_app.UNASSIGNED.{i:02}', app_id=None) for i in range(1, 13)]
    if len(app_tasks) != 12 or len({t['task_id'] for t in app_tasks}) != 12:
        raise ValueError('The multi-app protocol needs 12 distinct task templates')
    assigned = all(t.get('app_id') for t in app_tasks)
    if assigned and len({t['app_id'] for t in app_tasks}) < 4:
        raise ValueError('The multi-app protocol needs at least four apps')
    phases = [dict(phase='settings', tasks=[dict(task_id=t, app_id='com.android.settings')
                   for t in SETTINGS_TASKS], conditions=('normal', 'no_effect', 'delayed'),
                   repeats=3, arms=('A', 'B'), budget_ms=120000, planner_arm='graph'),
              dict(phase='multi_app', tasks=app_tasks,
                   conditions=('normal', 'delayed', 'target_changed'), repeats=2,
                   arms=('A', 'B'), budget_ms=180000, planner_arm='existing_app_task')]
    if include_ablation:
        phases.append(dict(phase='ablation', tasks=[dict(task_id=t, app_id='com.android.settings')
                      for t in SETTINGS_TASKS], conditions=('normal', 'no_effect', 'delayed'),
                      repeats=2, arms=('C', 'D'), budget_ms=120000, planner_arm='graph'))
    trials = []
    rng = Random(seed)
    for phase in phases:
        blocks = [(task, condition, repeat) for task in phase['tasks']
                  for condition in phase['conditions'] for repeat in range(1, phase['repeats'] + 1)]
        # Exactly balanced order within each phase; seed fixes its assignment to blocks.
        orders = [phase['arms'] if i % 2 == 0 else phase['arms'][::-1] for i in range(len(blocks))]
        rng.shuffle(orders)
        for (task, condition, repeat), order in zip(blocks, orders):
            # Ablation intentionally shares the first two settings repetitions with A/B.
            pairing_phase = 'settings' if phase['phase'] == 'ablation' else phase['phase']
            pair_id = f'{pairing_phase}:{task["task_id"]}:{condition}:{repeat}'
            intervention_seed = int(sha256(f'{seed}:{pair_id}'.encode()).hexdigest()[:8], 16)
            for position, arm in enumerate(order, 1):
                trials.append(dict(trial_id=f'{pair_id}:{arm}', pair_id=pair_id, phase=phase['phase'],
                    **task, condition=condition, repeat=repeat, arm=arm, position=position,
                    intervention_seed=intervention_seed, budget_ms=phase['budget_ms'],
                    planner_arm=phase['planner_arm'], status='NOT_RUN',
                    readiness='READY_FOR_CAPABILITY_CHECK' if task['app_id'] else 'TASK_AND_APP_UNASSIGNED'))
    return dict(schema_version=VERSION, seed=seed, arms={
        'A': 'Current CLICK including existing safety and pre/post verification',
        'B': 'A plus episode memory, new meta-state inputs and selective recovery',
        'C': 'B without newly added meta-state event input; existing checks retained',
        'D': 'B with reflection on every action, same model and input contract'},
        primary_trials=252, optional_ablation_trials=72 if include_ablation else 0,
        development_shadow_trials=20, trials=trials,
        constraints=['Same planner, parser, model, tools, FE and budgets within each A/B pair.',
            'AI2 reuse disabled equally; no silent model or planner fallback.',
            'Confirm independent oracle, intervention implementation and app capability before execution.',
            'Two multi-app applications must be held out from controller development.',
            'Scripted and natural failures must be reported separately.',
            'Shadow replay is diagnosis evidence, not counterfactual end-to-end success.'],
        analysis='Paired episode differences; bootstrap clusters are task templates, not actions.',
        decision='NOT_RUN')


def _number(value, name, *, optional=False, integer=False):
    if optional and value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not isfinite(value) or value < 0:
        raise ValueError(f'{name} must be a finite nonnegative number')
    if integer and value != int(value):
        raise ValueError(f'{name} must be an integer')
    return int(value) if integer else float(value)


def validate_trial(raw):
    """Validate one completed/failed episode, leaving missing measurement as null."""
    item = dict(raw)
    for field in ('trial_id', 'pair_id', 'task_id', 'app_id', 'condition', 'policy_version',
                  'oracle_source', 'driver_mode', 'planner_arm'):
        if not isinstance(item.get(field), str) or not item[field].strip():
            raise ValueError(f'{field} is required')
    if item.get('arm') not in ARMS:
        raise ValueError('arm must be A, B, C or D')
    if item.get('source') not in SOURCES:
        raise ValueError('Unknown evidence source')
    for field in ('independent_oracle', 'fault_injected', 'declared_done'):
        if not isinstance(item.get(field), bool):
            raise ValueError(f'{field} must be boolean')
    if item.get('oracle_success') is not None and not isinstance(item['oracle_success'], bool):
        raise ValueError('oracle_success must be boolean or null (unverifiable)')
    for field in ('runtime_verified_offer', 'false_verified_offer'):
        if item.get(field) is not None and not isinstance(item[field], bool):
            raise ValueError(f'{field} must be boolean or null')
        item.setdefault(field, None)
    if item.get('oracle_observable') is not None and not isinstance(item['oracle_observable'], bool):
        raise ValueError('oracle_observable must be boolean or null')
    item.setdefault('oracle_observable', None)
    if item['false_verified_offer'] is True and item['runtime_verified_offer'] is not True:
        raise ValueError('A false verified offer requires an observed runtime verified offer')
    count = _number(item.get('oracle_final_match_count'), 'oracle_final_match_count', optional=True, integer=True)
    item['oracle_final_match_count'] = count
    item['ambiguous_oracle'] = count is not None and count > 1
    if item['ambiguous_oracle']:
        item.setdefault('reported_oracle_success', item.get('oracle_success'))
        item['oracle_success'] = None
    elif item['oracle_observable'] is False and item.get('oracle_success') is False:
        item.setdefault('reported_oracle_success', item['oracle_success'])
        item['oracle_success'] = None
    # A current unavailable read must not erase an earlier stable positive goal
    # observation. It also cannot establish absence for a false-offer judgment.
    definite_absence = count == 0 and item['oracle_observable'] is not False or (
        count is None and item['oracle_observable'] is None and item.get('oracle_success') is False)
    definite_presence = count == 1 and item['oracle_observable'] is not False or (
        count is None and item['oracle_observable'] is None and item.get('oracle_success') is True)
    reported_offer_flag = item['false_verified_offer']
    if item['runtime_verified_offer'] is False:
        item['false_verified_offer'] = False
    elif item['runtime_verified_offer'] is None:
        item['false_verified_offer'] = None
    elif not item['independent_oracle'] or item['ambiguous_oracle']:
        item['false_verified_offer'] = None
    elif item['false_verified_offer'] is True and not definite_absence:
        item['false_verified_offer'] = None
    elif item['false_verified_offer'] is False and not definite_presence:
        item['false_verified_offer'] = None
    if item['false_verified_offer'] != reported_offer_flag:
        item.setdefault('reported_false_verified_offer', reported_offer_flag)
    item['false_verified_offer_evidence_rejected'] = (
        item.get('reported_false_verified_offer') is True and item['false_verified_offer'] is None)
    item['unknown_verified_offer'] = item['runtime_verified_offer'] is True and item['false_verified_offer'] is None
    if item.get('oracle_source') in {'controller', 'runtime_verdict', 'model_self_report'} and item['independent_oracle']:
        raise ValueError('A controller/model verdict is not an independent oracle')
    item['elapsed_ms'] = _number(item.get('elapsed_ms'), 'elapsed_ms')
    item['budget_ms'] = _number(item.get('budget_ms'), 'budget_ms')
    if item['budget_ms'] <= 0:
        raise ValueError('budget_ms must be positive')
    for field in ('model_wait_ms', 'model_calls', 'tokens', 'wrong_target_count',
                  'recovery_attempts', 'recovery_successes', 'unnecessary_recovery_count'):
        item[field] = _number(item.get(field), field, optional=True, integer=field != 'model_wait_ms')
    if item['recovery_successes'] is not None and (item['recovery_attempts'] is None
            or item['recovery_successes'] > item['recovery_attempts']):
        raise ValueError('recovery_successes requires a matching recovery_attempts count')
    if item['model_wait_ms'] is not None and item['model_wait_ms'] > item['elapsed_ms']:
        raise ValueError('Sequential measured model wait cannot exceed episode elapsed time')
    for field in ('started_at_ms', 'ended_at_ms'):
        item[field] = _number(item.get(field), field, optional=True)
    if (item['started_at_ms'] is None) != (item['ended_at_ms'] is None):
        raise ValueError('Both episode clock endpoints must be supplied together')
    if item['started_at_ms'] is not None:
        if item['ended_at_ms'] < item['started_at_ms']:
            raise ValueError('Episode clock must be monotonic')
        if abs(item['ended_at_ms'] - item['started_at_ms'] - item['elapsed_ms']) > 1:
            raise ValueError('Episode endpoints disagree with elapsed_ms')
    item['verified_success'] = bool(item.get('oracle_success') is True
        and item['independent_oracle'] and item['elapsed_ms'] <= item['budget_ms'])
    item['penalized_time_ms'] = item['elapsed_ms'] if item['verified_success'] else item['budget_ms']
    item['false_done'] = item['declared_done'] and item.get('oracle_success') is False and item['independent_oracle']
    item['unverifiable_done'] = item['declared_done'] and (item.get('oracle_success') is None or not item['independent_oracle'])
    return item


def percentile(values, quantile):
    return sorted(values)[max(0, ceil(len(values) * quantile) - 1)] if values else None


def metrics(items):
    n = len(items)
    offers_adjudicated = sum(t['runtime_verified_offer'] is True
        and t['false_verified_offer'] is not None for t in items)
    false_offers = sum(t['false_verified_offer'] is True for t in items)
    result = dict(trials=n, verified_successes=sum(t['verified_success'] for t in items),
        success_rate=sum(t['verified_success'] for t in items) / n if n else None,
        independent_oracle_trials=sum(t['independent_oracle'] for t in items),
        unknown_oracle_trials=sum(t.get('oracle_success') is None for t in items),
        ambiguous_oracle_trials=sum(t['ambiguous_oracle'] for t in items),
        final_oracle_observable_trials=sum(t['oracle_observable'] is True for t in items),
        final_oracle_unobservable_trials=sum(t['oracle_observable'] is False for t in items),
        final_oracle_coverage_unmeasured_trials=sum(t['oracle_observable'] is None for t in items),
        positive_goal_evidence_with_final_unobservable=sum(t['verified_success'] and t['oracle_observable'] is False for t in items),
        runtime_verified_offers=sum(t['runtime_verified_offer'] is True for t in items),
        runtime_verified_offer_measured_trials=sum(t['runtime_verified_offer'] is not None for t in items),
        false_verified_offers=false_offers,
        adjudicated_verified_offers=offers_adjudicated,
        false_verified_offer_rate_per_adjudicated_offer=false_offers / offers_adjudicated if offers_adjudicated else None,
        false_verified_offer_measured_trials=sum(t['false_verified_offer'] is not None for t in items),
        unknown_verified_offers=sum(t['unknown_verified_offer'] for t in items),
        false_verified_offer_evidence_rejected=sum(t['false_verified_offer_evidence_rejected'] for t in items),
        false_done=sum(t['false_done'] for t in items),
        unverifiable_done=sum(t['unverifiable_done'] for t in items),
        provider_error_trials=sum(bool(t.get('provider_error')) for t in items),
        fault_injected_trials=sum(t['fault_injected'] for t in items),
        mean_penalized_time_ms=mean(t['penalized_time_ms'] for t in items) if n else None,
        elapsed_p50_ms=median(t['elapsed_ms'] for t in items) if n else None,
        elapsed_p95_ms=percentile([t['elapsed_ms'] for t in items], .95))
    for field in ('model_wait_ms', 'model_calls', 'tokens', 'wrong_target_count',
                  'recovery_attempts', 'recovery_successes', 'unnecessary_recovery_count'):
        observed = [t[field] for t in items if t[field] is not None]
        result[field + '_measured_trials'] = len(observed)
        result[field + '_total'] = sum(observed) if observed else None
    return result


def _paired(items, left, right, *, bootstrap, seed):
    blocks = defaultdict(dict)
    for item in items:
        if item['arm'] not in (left, right):
            continue
        if item['arm'] in blocks[item['pair_id']]:
            raise ValueError(f'Duplicate {item["arm"]} trial in pair {item["pair_id"]}')
        blocks[item['pair_id']][item['arm']] = item
    pairs, rejected = [], []
    for key, block in sorted(blocks.items()):
        if left not in block or right not in block:
            rejected.append(dict(pair_id=key, reason='MISSING_ARM'))
            continue
        a, b = block[left], block[right]
        match_fields = tuple(k for k in MATCH_FIELDS if k != 'model_version') + (
            ('configured_model',) if a.get('configured_model') or b.get('configured_model') else ('model_version',))
        match_fields += tuple(k for k in ('experiment_patch_sha', 'patch_sha') if k in a or k in b)
        missing = [k for k in match_fields if a.get(k) is None or b.get(k) is None
                   or k in {'experiment_patch_sha', 'patch_sha'} and (not a.get(k) or not b.get(k))]
        mismatch = [k for k in match_fields if a.get(k) != b.get(k)]
        if missing or mismatch:
            rejected.append(dict(pair_id=key, reason='INCOMPLETE_OR_DIFFERENT_CONTEXT',
                                 missing_fields=missing, mismatched_fields=mismatch))
            continue
        pairs.append((a, b))
    clusters = defaultdict(list)
    for a, b in pairs:
        clusters[(a['app_id'], a['task_id'])].append((a, b))

    def differences(sample):
        return dict(success_rate_pp=100 * mean(int(b['verified_success']) - int(a['verified_success']) for a, b in sample),
                    mean_penalized_time_ms=mean(b['penalized_time_ms'] - a['penalized_time_ms'] for a, b in sample))

    point = differences(pairs) if pairs else None
    intervals = None
    if len(clusters) >= 2 and bootstrap > 0:
        rng = Random(seed)
        keys = sorted(clusters)
        samples = [differences([p for _ in keys for p in clusters[rng.choice(keys)]]) for _ in range(bootstrap)]
        intervals = {k: [percentile([v[k] for v in samples], .025),
                         percentile([v[k] for v in samples], .975)] for k in point}
    return dict(left=left, right=right, paired_episodes=len(pairs), task_clusters=len(clusters),
        difference_right_minus_left=point, cluster_bootstrap_95ci=intervals,
        rejected_pairs=rejected,
        realized_injection_mismatch_pairs=sum(a['fault_injected'] != b['fault_injected'] for a, b in pairs),
        interpretation='EXPLORATORY_NOT_POWERED_SUPERIORITY',
        left_metrics=metrics([a for a, _ in pairs]), right_metrics=metrics([b for _, b in pairs]))


def evaluate(records, *, bootstrap=2000, seed=20261120):
    """Evaluate actual episode records; do not pool different evidence modes/pipelines."""
    if not isinstance(bootstrap, int) or isinstance(bootstrap, bool) or not 0 <= bootstrap <= 20000:
        raise ValueError('bootstrap must be between 0 and 20000')
    trials = [validate_trial(t) for t in records]
    ids = [t['trial_id'] for t in trials]
    if len(ids) != len(set(ids)):
        raise ValueError('Duplicate trial_id')
    groups = defaultdict(list)
    for trial in trials:
        groups[(trial['source'], trial['planner_arm'], trial['driver_mode'])].append(trial)
    reports = []
    for (source, planner, driver), items in sorted(groups.items()):
        for arm in ARMS:
            if len({t['policy_version'] for t in items if t['arm'] == arm}) > 1:
                raise ValueError('Different versions of the same arm must be analyzed separately')
        slices = defaultdict(list)
        for item in items:
            slices[(item['app_id'], item['condition'], item['fault_injected'])].append(item)
        report = dict(source=source, planner_arm=planner, driver_mode=driver,
            by_arm={arm: metrics([t for t in items if t['arm'] == arm]) for arm in ARMS},
            paired=[_paired(items, l, r, bootstrap=bootstrap, seed=seed) for l, r in (('A', 'B'), ('B', 'C'), ('B', 'D'))],
            conditions=[dict(app_id=app, condition=condition, fault_injected=injected,
                by_arm={arm: metrics([t for t in values if t['arm'] == arm]) for arm in ARMS})
                for (app, condition, injected), values in sorted(slices.items())])
        reports.append(report)
    decision = _decision(trials, reports)
    return dict(schema_version=VERSION, episode_count=len(trials), evidence_groups=reports,
        go_no_go=decision, limitations=[
            'Scripted-driver and controlled-contract results do not establish product-level task completion.',
            'A null model measurement is unmeasured, not zero; local policy timing is not model/API latency.',
            'Normal-path speed must be read separately from injected-failure conditions.',
            'Failed, provider-error and unverifiable episodes remain in all-trial denominators.',
            'Pairing requires same initial state, intervention seed, model, device and base revision.',
            'Confidence intervals resample task templates; repeated steps are not independent samples.',
            'Scripted driver episodes cannot answer middle/older-adult usability questions.',
            'Zero false DONE is uninformative when the driver never confirms user results; inspect false verified offers.',
            'Multiple independent goal matches are ambiguous and count as unverifiable, not success.',
            'Deployment eligibility also requires integration, multi-app holdouts and external user validation.'])


def _decision(trials, reports):
    """Fixed pilot-stage gates, not an automatic product release authorization.

    A scripted-driver result can justify an expanded device experiment, never a
    product rollout. The same requirements are applied before viewing outcomes.
    """
    device_reports = [r for r in reports if r['source'] in
                      {'real_device_scripted_driver', 'real_device_product'}]
    gates = []
    for report in device_reports:
        comparison = report['paired'][0]
        a, b = comparison['left_metrics'], comparison['right_metrics']
        n = comparison['paired_episodes']
        enough = n >= 6 and comparison['task_clusters'] >= 2
        oracle_complete = n > 0 and all(m['independent_oracle_trials'] == n
            and m['unknown_oracle_trials'] == 0 for m in (a, b))
        safety_measured = n > 0 and all(m['wrong_target_count_measured_trials'] == n for m in (a, b))
        offer_measured = n > 0 and all(m['runtime_verified_offer_measured_trials'] == n
            and not m['unknown_verified_offers'] for m in (a, b))
        safe = safety_measured and all(not m['false_done'] and not m['unverifiable_done']
            and m['wrong_target_count_total'] == 0 for m in (a, b))
        delta, ci = comparison['difference_right_minus_left'], comparison['cluster_bootstrap_95ci']
        directional_benefit = bool(delta and delta['success_rate_pp'] >= 0
            and delta['mean_penalized_time_ms'] <= 0
            and (delta['success_rate_pp'] > 0 or delta['mean_penalized_time_ms'] < 0))
        supported_benefit = bool(ci and
            (ci['success_rate_pp'][0] > 0 and ci['mean_penalized_time_ms'][1] <= 0
             or ci['success_rate_pp'][0] >= 0 and ci['mean_penalized_time_ms'][1] < 0))
        normal = [c for c in report['conditions'] if c['condition'] == 'normal' and not c['fault_injected']
                  and c['by_arm']['A']['trials'] and c['by_arm']['B']['trials']]
        # This is an engineering pilot threshold, not a statistical noninferiority margin.
        normal_overhead_ok = bool(normal) and all(c['by_arm']['B']['elapsed_p95_ms']
            <= c['by_arm']['A']['elapsed_p95_ms'] * 1.10 for c in normal)
        if b['false_verified_offers']:
            status = 'NO_GO_FIX_COMPLETION_GROUNDING'
        elif not enough or not oracle_complete or not safety_measured or not offer_measured:
            status = 'NEEDS_MATCHED_DEVICE_DATA'
        elif not safe:
            status = 'STOP_AND_FIX_SAFETY'
        elif not directional_benefit:
            status = 'NO_GO_NO_OBSERVED_BENEFIT'
        elif not normal_overhead_ok:
            status = 'NO_GO_NORMAL_PATH_OVERHEAD'
        elif not supported_benefit:
            status = 'NEEDS_MORE_EFFICACY_DATA'
        else:
            status = 'GO_EXPANDED_DEVICE_VALIDATION'
        gates.append(dict(source=report['source'], planner_arm=report['planner_arm'],
            driver_mode=report['driver_mode'], status=status, matched_pairs=n,
            task_clusters=comparison['task_clusters'], enough_matched_episodes=enough,
            independent_oracles_complete=oracle_complete, wrong_target_measurement_complete=safety_measured,
            verified_offer_measurement_complete=offer_measured,
            false_verified_offers_current=a['false_verified_offers'], false_verified_offers_candidate=b['false_verified_offers'],
            unknown_verified_offers_current=a['unknown_verified_offers'], unknown_verified_offers_candidate=b['unknown_verified_offers'],
            safety_pass=safe, observed_benefit_without_regression=directional_benefit,
            cluster_ci_supports_benefit=supported_benefit, normal_path_p95_within_10_percent=normal_overhead_ok))
    unsafe_b = any(t['arm'] == 'B' and (t['false_done'] or t['unverifiable_done']
                    or (t['wrong_target_count'] or 0) > 0) for t in trials)
    false_offer_b = any(t['arm'] == 'B' and t['false_verified_offer'] is True for t in trials)
    continuation = 'NEEDS_EPISODES' if not trials else 'NEEDS_MATCHED_DEVICE_DATA'
    if gates:
        statuses = {g['status'] for g in gates}
        # A good result in one pipeline must not hide an unresolved second pipeline.
        continuation = next((s for s in ('STOP_AND_FIX_SAFETY', 'NO_GO_FIX_COMPLETION_GROUNDING', 'NO_GO_NORMAL_PATH_OVERHEAD',
            'NO_GO_NO_OBSERVED_BENEFIT', 'NEEDS_MATCHED_DEVICE_DATA', 'NEEDS_MORE_EFFICACY_DATA')
            if s in statuses), 'GO_EXPANDED_DEVICE_VALIDATION')
    if unsafe_b:
        continuation = 'STOP_AND_FIX_SAFETY'
    elif false_offer_b:
        continuation = 'NO_GO_FIX_COMPLETION_GROUNDING'
    product = [t for t in trials if t['source'] == 'real_device_product']
    settings = [t for t in product if t['task_id'] in SETTINGS_TASKS]
    cells = defaultdict(int)
    for t in settings:
        if t['arm'] in ('A', 'B'):
            cells[(t['task_id'], t['condition'], t['arm'])] += 1
    settings_complete = all(cells[(task, condition, arm)] >= 3 for task in SETTINGS_TASKS
        for condition in ('normal', 'no_effect', 'delayed') for arm in ('A', 'B'))
    multi = [t for t in product if t['task_id'] not in SETTINGS_TASKS and t['arm'] in ('A', 'B')]
    tasks = {(t['task_id'], t['app_id']) for t in multi}
    multi_cells = defaultdict(int)
    for t in multi:
        multi_cells[(t['task_id'], t['app_id'], t['condition'], t['arm'])] += 1
    multi_complete = len(tasks) >= 12 and len({app for _, app in tasks}) >= 4 and all(
        multi_cells[(task, app, condition, arm)] >= 2 for task, app in tasks
        for condition in ('normal', 'delayed', 'target_changed') for arm in ('A', 'B'))
    return dict(scope='opt_in_native_policy_pilot', deployment='NEEDS_REVIEW' if settings_complete and multi_complete else 'NO_GO',
        reason='SEPARATE_PRODUCT_AND_USER_REVIEW_REQUIRED' if settings_complete and multi_complete
               else 'PRODUCTION_PROTOCOL_AND_EXTERNAL_VALIDATION_INCOMPLETE',
        experiment_continuation=continuation, stage_gates=gates,
        continuation_authorizes_only_an_expanded_experiment=True,
        pilot_rule=dict(minimum_matched_pairs=6, minimum_task_clusters=2,
            independent_oracle_required=True, measured_wrong_targets_required=True,
            candidate_false_verified_offers_must_be_zero=True,
            verified_offer_measurement_required=True,
            maximum_normal_path_p95_overhead_percent=10,
            positive_cluster_ci_required_for_expansion=True),
        product_settings_trials=len(settings), settings_matrix_complete=settings_complete,
        product_multi_app_trials=len(multi), multi_app_matrix_complete=multi_complete,
        planned_product_settings_trials=108, planned_product_multi_app_trials=144,
        multi_app_task_assignment='MUST_BE_FROZEN_BEFORE_PRODUCT_EVALUATION',
        older_adult_user_study='NOT_ASSESSED_BY_THIS_EPISODE_ANALYZER')
