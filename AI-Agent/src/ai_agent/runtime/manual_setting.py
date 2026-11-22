"""Observed native changes are evidence of an outcome, never of an executed app action."""
from math import isclose

from ai_agent.runtime.task_success import numeric, success_contract, technical_success
from ai_agent.runtime.verification import applied_value, valid_slider_value

SOURCES = {
    'font_size': 'android.provider.Settings.System.FONT_SCALE',
    'brightness': 'android.provider.Settings.System.SCREEN_BRIGHTNESS',
    'volume': 'android.media.AudioManager.STREAM_MUSIC',
}
PACKAGES = {'com.android.settings', 'com.samsung.android.settings'}


def capture_baseline(previous, *, graph, goal, guide, match, nodes, snapshot, episode_id):
    """Keep the first server-observed target/value across confirmations and native redraws."""
    spec = success_contract(goal.goal_type)
    setting = spec.get('setting')
    if setting not in SOURCES or spec.get('kind') != 'persisted_value' or not episode_id:
        return None
    if previous and previous.get('episode_id') == episode_id and previous.get('goal_type') == goal.goal_type:
        return dict(previous)
    transition = next((t for t in graph.transitions if t.transition_id == match.transition_id), None)
    node = next((n for n in nodes if n.node_id == guide.target_node_id), None)
    value = applied_value(snapshot.applied_settings, setting)
    if (guide.command_type.value not in {'highlight', 'confirmation'} or not guide.requires_user_action
            or guide.render_policy.get('vision_derived') or 'vision' in match.match_method.lower()
            or not transition or transition.action_type.value != 'drag' or not transition.completes_task
            or node is None or node.node_id.startswith('vision_') or not node.enabled or node.role != 'slider' or node.package_name not in PACKAGES
            or node.window_id is None or not node.bounds or guide.target_bounds != node.bounds
            or node.range_min is None or node.range_max is None or node.range_min >= node.range_max
            or not valid_slider_value(node.value, node.range_min, node.range_max) or value is None
            or not snapshot.observation_id or not numeric(snapshot.captured_at_ms)):
        return None
    return {'episode_id': episode_id, 'goal_type': goal.goal_type, 'setting': setting,
            'guide_command_id': guide.guide_command_id, 'target_key': match.target_key,
            'baseline_observation_id': snapshot.observation_id, 'baseline_captured_at_ms': snapshot.captured_at_ms,
            'package_name': node.package_name, 'target_node_id': node.node_id,
            'target_resource_id': node.resource_id, 'target_class_name': node.class_name,
            'window_id': node.window_id, 'range_min': node.range_min, 'range_max': node.range_max,
            'control_value': node.value, 'setting_value': value}


def verify_evidence(evidence, baseline, *, episode_id, goal_type, latest_snapshot):
    """Validate two fresh, settled readings against the stored server baseline."""
    if not baseline or baseline.get('episode_id') != episode_id or baseline.get('goal_type') != goal_type:
        return None, 'baseline_unavailable'
    if (evidence.get('guide_command_id') != baseline['guide_command_id']
            or evidence.get('baseline_observation_id') != baseline['baseline_observation_id']):
        return None, 'baseline_mismatch'
    first, settled = evidence['observations']
    if (not latest_snapshot or first['observation_id'] != latest_snapshot.get('observation_id')
            or first['captured_at_ms'] != latest_snapshot.get('captured_at_ms')):
        return None, 'observation_not_received'
    if (first['control_value'] != (latest_snapshot.get('slider_values') or {}).get(first['target_node_id'])
            or first['applied_value'] != applied_value(latest_snapshot.get('applied_settings'), baseline['setting'])):
        return None, 'observed_value_mismatch'
    observations = (first, settled)
    before = baseline['baseline_captured_at_ms']
    if (len({baseline['baseline_observation_id'], first['observation_id'], settled['observation_id']}) != 3
            or not before < first['captured_at_ms'] < settled['captured_at_ms']
            or not 250 <= settled['captured_at_ms'] - first['captured_at_ms'] <= 10000):
        return None, 'observation_timing_invalid'
    setting = baseline['setting']
    for frame in observations:
        same_target = (frame['package_name'] == baseline['package_name']
                and frame.get('target_class_name') == baseline.get('target_class_name')
                and frame.get('range_min') == baseline['range_min'] and frame.get('range_max') == baseline['range_max'])
        if baseline.get('target_resource_id'):
            same_target = same_target and frame.get('target_resource_id') == baseline['target_resource_id']
        else:
            same_target = (same_target and not frame.get('target_resource_id')
                    and frame['target_node_id'] == baseline['target_node_id'] and frame['window_id'] == baseline['window_id'])
        if not same_target:
            return None, 'target_mismatch'
        if frame['applied_source'] != SOURCES[setting]:
            return None, 'setting_source_mismatch'
        applied = frame['applied_value']
        if setting == 'font_size':
            if not 0 < applied <= 10:
                return None, 'applied_value_invalid'
        elif not float(applied).is_integer() or not 0 <= applied <= (65535 if setting == 'brightness' else 1000):
            return None, 'applied_value_invalid'
        if not valid_slider_value(frame['control_value'], baseline['range_min'], baseline['range_max']):
            return None, 'control_value_invalid'
    if (not isclose(first['control_value'], settled['control_value'], rel_tol=1e-6)
            or not isclose(first['applied_value'], settled['applied_value'], rel_tol=1e-6)):
        return None, 'value_not_settled'
    after = settled['applied_value']
    if setting != 'font_size':
        if not float(after).is_integer():
            return None, 'applied_value_invalid'
        after = int(after)
    direction = success_contract(goal_type)['direction']
    proof = {'status': 'CHANGED', 'reason': 'target_value_increased' if direction == 'increase' else 'target_value_decreased',
             'goal_type': goal_type, 'setting': setting, 'evidence_source': 'manual_native_observation',
             'execution_attribution': 'observed_only', 'episode_id': episode_id,
             'guide_command_id': baseline['guide_command_id'], 'target_key': baseline['target_key'],
             'baseline_observation_id': baseline['baseline_observation_id'], 'observation_id': first['observation_id'],
             'settled_observation_id': settled['observation_id'], 'baseline_captured_at_ms': before,
             'observed_at_ms': first['captured_at_ms'], 'verified_at_ms': settled['captured_at_ms'],
             'baseline_setting_value': baseline['setting_value'], 'setting_value': after,
             'range_min': baseline['range_min'], 'range_max': baseline['range_max'],
             'baseline_control_value': baseline['control_value'], 'control_value': settled['control_value']}
    if not technical_success(proof, goal_type):
        return None, 'requested_change_not_observed'
    return proof, 'native_manual_change_verified'
