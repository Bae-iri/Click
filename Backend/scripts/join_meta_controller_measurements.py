"""Join native episodes to bounded facade logs without inventing missing measures.

The controller arm remains assigned even if transport/provider failure prevents
the intended perturbation. Actual perturbation delivery is a reported outcome,
not a condition used to drop a trial from intention-to-treat analysis.
"""
import argparse
from collections import defaultdict
from hashlib import sha256
import json
from math import isfinite
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.services.meta_controller_experiment import validate_trial


CONDITIONS = {'normal': 'normal', 'noeffect': 'no_effect', 'no_effect': 'no_effect',
              'delay': 'dispatch_delay', 'dispatch_delay': 'dispatch_delay'}


def _hash(value):
    return sha256(str(value).encode()).hexdigest()


def _measurement(value, field, *, integer=False):
    if value is None:
        return None
    if type(value) not in (int, float) or not isfinite(value) or value < 0 or integer and value != int(value):
        raise ValueError(f'Invalid measured {field}')
    return int(value) if integer else float(value)


def join_measurements(episodes, provider_calls, context):
    """Return normalized episodes plus an audit. This function makes no calls."""
    for key in ('device_scope', 'base_code_revision', 'patch_sha'):
        if not isinstance(context.get(key), str) or not context[key].strip():
            raise ValueError(f'Context requires {key}')
    capture_complete = context.get('provider_capture_complete') is True
    provider = defaultdict(list)
    seen_requests = set()
    for call in provider_calls:
        ref, request_ref = call.get('episode_ref'), call.get('request_ref')
        if not isinstance(ref, str) or not isinstance(request_ref, str) or not ref or not request_ref:
            raise ValueError('Provider logs require hashed episode_ref and request_ref')
        identity = (ref, request_ref)
        if identity in seen_requests:
            raise ValueError('Duplicate provider request_ref; resolve capture duplication before analysis')
        seen_requests.add(identity)
        _measurement(call.get('model_calls'), 'model_calls', integer=True)
        _measurement(call.get('model_wait_ms'), 'model_wait_ms')
        provider[ref].append(call)
    normalized, used_refs, seen_trials, seen_blocks = [], set(), set(), set()
    for raw in episodes:
        item = dict(raw)
        trial_id, pair_id, arm = item.get('trial_id'), item.get('pair_id'), item.get('arm')
        if not isinstance(trial_id, str) or not trial_id or trial_id in seen_trials:
            raise ValueError('Missing or duplicate trial_id')
        if (pair_id, arm) in seen_blocks:
            raise ValueError('Duplicate arm in paired episode block')
        seen_trials.add(trial_id)
        seen_blocks.add((pair_id, arm))
        original = item.get('condition')
        if original not in CONDITIONS:
            raise ValueError('Unknown native condition; specify its intervention semantics explicitly')
        item['assigned_native_condition'] = original
        item['condition'] = CONDITIONS[original]
        item['intervention_semantics'] = {'normal': 'no_injected_fault',
            'no_effect': 'first_native_dispatch_accepted_without_execution',
            'dispatch_delay': 'first_native_dispatch_deferred_not_ui_render_delay'}[item['condition']]
        item.update(source='real_device_scripted_driver', device_scope=context['device_scope'],
            base_code_revision=context['base_code_revision'], experiment_patch_sha=context['patch_sha'],
            provider_capture_complete=capture_complete)
        item['intervention_seed'] = int(_hash(pair_id)[:8], 16)
        if context.get('configured_model'):
            item['configured_model'] = context['configured_model']
        # Setup failures retain their denominator but cannot be paired by a made-up starting screen.
        item.setdefault('initial_state_hash', None)
        runtime_id = item.pop('runtime_task_id', None)
        episode_ref = _hash(runtime_id)[:24] if isinstance(runtime_id, str) and runtime_id else None
        item['runtime_task_ref'] = episode_ref
        calls = provider.get(episode_ref, []) if episode_ref else []
        if episode_ref and calls:
            used_refs.add(episode_ref)
        setup_failed = item.get('setup_status') in {'failed', 'error'} and not episode_ref
        if setup_failed:
            # The driver's initial false is a placeholder, not an observation of
            # goal absence when setup ended before the runtime/oracle episode.
            item.setdefault('reported_oracle_success', item.get('oracle_success'))
            item['oracle_success'] = None
            item['oracle_measurement_status'] = 'NOT_EVALUATED_SETUP_FAILURE'
        explicitly_local = item.get('model_measurement_source') == 'no_remote_calls'
        zero_calls_known = setup_failed or explicitly_local or capture_complete
        item['model_measurement_source'] = 'facade_log' if calls else (
            'no_calls_confirmed_complete_capture' if zero_calls_known else 'provider_log_incomplete')
        measured_counts = [c.get('model_calls') for c in calls]
        count_known = all(v is not None for v in measured_counts) if calls else zero_calls_known
        item['model_calls'] = sum(measured_counts) if calls and count_known else 0 if not calls and zero_calls_known else None
        waits = [c.get('model_wait_ms') for c in calls]
        work_known = all(v is not None for v in waits) if calls else zero_calls_known
        work = sum(waits) if calls and work_known else 0 if not calls and zero_calls_known else None
        item['server_provider_work_ms'] = work
        elapsed = _measurement(item.get('elapsed_ms'), 'elapsed_ms')
        if elapsed is None:
            raise ValueError('Episode elapsed_ms is required')
        item['model_wait_ms'] = work if work is not None and work <= elapsed else None
        item['provider_work_exceeds_episode_elapsed'] = work is not None and work > elapsed
        item['model_wait_interpretation'] = ('sum_server_call_duration_not_device_wait'
            if item['model_wait_ms'] is not None else 'unmeasured_or_provider_work_outside_episode_window')
        actual_models = sorted({c['model'] for c in calls if c.get('model_calls') and isinstance(c.get('model'), str) and c['model']})
        item['models_observed'] = actual_models
        if item['model_calls'] == 0:
            item['model_version'] = 'not_called'
        elif len(actual_models) == 1 and all(c.get('model') for c in calls if c.get('model_calls')):
            item['model_version'] = actual_models[0]
        else:
            item['model_version'] = None
        item['provider_error'] = any(c.get('provider_error') is True for c in calls)
        item['provider_error_count'] = sum(c.get('provider_error') is True for c in calls)
        item['plan_or_parse_error_count'] = sum(c.get('plan_or_parse_error') is True for c in calls)
        item['provider_log_rows'] = len(calls)
        item['provider_reasons'] = sorted({c['reason'] for c in calls if isinstance(c.get('reason'), str)})
        item['provider_http_statuses'] = sorted({c['provider_http_status'] for c in calls
                                                if type(c.get('provider_http_status')) is int})
        handler_times = [c.get('request_total_handler_ms') for c in calls]
        item['server_handler_work_ms'] = sum(handler_times) if calls and all(
            _measurement(v, 'request_total_handler_ms') is not None for v in handler_times) else None
        # Rejections are not a measurement of wrong accepted targets.
        item.setdefault('wrong_target_count', None)
        item.setdefault('tokens', None)
        for field in ('recovery_attempts', 'recovery_successes', 'unnecessary_recovery_count'):
            item.setdefault(field, None)
        if not isinstance(item.get('policy_version'), str):
            raise ValueError('Episode policy_version is required')
        item['policy_version'] = item['policy_version'] + ':' + arm + ':' + context['patch_sha']
        normalized.append(validate_trial(item))
    unmatched_refs = sorted(set(provider) - used_refs)
    audit = dict(schema_version='meta-controller-measurement-join.v1', episodes=len(normalized),
        provider_rows=len(provider_calls), matched_provider_rows=sum(len(provider[r]) for r in used_refs),
        unmatched_provider_episode_refs=unmatched_refs,
        unmatched_provider_rows=sum(len(provider[r]) for r in unmatched_refs),
        provider_capture_complete=capture_complete,
        episodes_with_unknown_model_calls=sum(t['model_calls'] is None for t in normalized),
        episodes_with_provider_work_outside_window=sum(t['provider_work_exceeds_episode_elapsed'] for t in normalized),
        setup_failures=sum(t.get('setup_status') in {'failed', 'error'} for t in normalized),
        notes=['Setup/provider failures stay in the all-trial denominator.',
            'Assigned condition and intervention seed define ITT blocks; realized injection is reported separately.',
            'dispatch_delay delays native execution; it is not evidence about delayed UI rendering.',
            'Missing model measurements stay null unless complete capture or pre-runtime/local execution establishes zero.',
            'Summed server work may extend past the episode and is not necessarily user-visible waiting.',
            'Wrong target count remains null unless directly measured; native rejection is not an accepted wrong target.'])
    return normalized, audit


def _read_jsonl(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--episodes', type=Path, required=True)
    parser.add_argument('--provider-calls', type=Path, required=True)
    parser.add_argument('--context', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--audit-output', type=Path)
    args = parser.parse_args(argv)
    try:
        episodes, audit = join_measurements(_read_jsonl(args.episodes), _read_jsonl(args.provider_calls),
                                            json.loads(args.context.read_text(encoding='utf-8')))
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(''.join(json.dumps(t, ensure_ascii=False) + '\n' for t in episodes), encoding='utf-8')
    audit_path = args.audit_output or args.output.with_name('join-audit.json')
    audit_path.parent.mkdir(parents=True, exist_ok=True)
    audit_path.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(output=str(args.output), audit=str(audit_path), episodes=len(episodes))))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
