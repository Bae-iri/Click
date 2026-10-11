"""Bounded data and predicate audit; NOT an emulator or Click end-to-end test.

Requires numpy, pillow, protobuf, google-crc32c and generated TF Example/Android
accessibility protobuf modules. See the accompanying review for source URLs.
Only metadata is written; downloaded screenshots remain outside the repository.
"""
from __future__ import annotations

import abc
import argparse
import ast
from collections import Counter, defaultdict
from dataclasses import replace
import gzip
import hashlib
import importlib.metadata
import io
import json
from pathlib import Path
import random
import shutil
import struct
import sys
from types import SimpleNamespace as NS
from typing import Any

import google_crc32c
import numpy as np
from PIL import Image


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def frames(data, allow_incomplete=False):
    offset = 0
    while offset < len(data):
        if offset + 12 > len(data):
            assert allow_incomplete
            return
        length = struct.unpack_from('<Q', data, offset)[0]
        end = offset + 12 + length
        if end + 4 > len(data):
            assert allow_incomplete
            return
        for body, expected in (
            (data[offset:offset+8], struct.unpack_from('<I', data, offset+8)[0]),
            (data[offset+12:end], struct.unpack_from('<I', data, end)[0]),
        ):
            crc = google_crc32c.value(body)
            assert (((crc >> 15) | (crc << 17)) + 0xa282ead8) & 0xffffffff == expected
        yield data[offset+12:end]
        offset = end + 4


def vals(features, key):
    feature = features[key]
    return list(getattr(feature, feature.WhichOneof('kind')).value)


def audit_aitw(cache, Example):
    path = cache / 'aitw-google-apps-shard0.gz'
    with gzip.open(path, 'rb') as stream:
        data = stream.read(512 * 1024 * 1024 + 1)
    assert len(data) <= 512 * 1024 * 1024
    fields, actions, dimensions = Counter(), Counter(), Counter()
    episodes = defaultdict(list)
    for record in frames(data):
        f = Example.FromString(record).features.feature
        fields.update(f.keys())
        width, height = vals(f, 'image/width')[0], vals(f, 'image/height')[0]
        channels = vals(f, 'image/channels')[0]
        raw_pixels = vals(f, 'image/encoded')[0]
        assert channels == 3 and len(raw_pixels) == width * height * channels
        image = Image.frombytes('RGB', (width, height), raw_pixels)
        dimensions[str(image.size)] += 1
        positions = vals(f, 'image/ui_annotations_positions')
        assert len(positions) % 4 == 0
        assert len(positions) // 4 == len(vals(f, 'image/ui_annotations_text'))
        assert len(positions) // 4 == len(vals(f, 'image/ui_annotations_ui_types'))
        action = vals(f, 'results/action_type')[0]
        actions[str(action)] += 1
        episode_id = vals(f, 'episode_id')[0]
        if isinstance(episode_id, bytes):
            episode_id = episode_id.decode()
        episodes[str(episode_id)].append({
            'step': vals(f, 'step_id')[0], 'declared_length': vals(f, 'episode_length')[0],
            'action_type': action,
        })
    summaries = []
    for ep_id, steps in episodes.items():
        ordered = sorted(steps, key=lambda x: x['step'])
        ids = [s['step'] for s in ordered]
        summaries.append({
            'episode_id': ep_id, 'records': len(steps),
            'declared_lengths': sorted(set(s['declared_length'] for s in steps)),
            'step_ids': ids,
            'contiguous': ids == list(range(ids[0], ids[0] + len(ids))),
            'declared_length_matches_observed': {s['declared_length'] for s in steps} == {len(steps)},
            'last_action_type': ordered[-1]['action_type'],
        })
    return {
        'source_file': path.name, 'compressed_bytes': path.stat().st_size,
        'sha256': sha(path), 'decompressed_bytes': len(data),
        'records': sum(actions.values()), 'episodes': len(episodes),
        'all_tfrecord_crcs_valid': True, 'all_raw_rgb_buffers_decoded': True,
        'annotation_array_lengths_aligned': True,
        'feature_occurrences': dict(sorted(fields.items())), 'actions': dict(actions),
        'dimensions': dict(dimensions), 'episode_summaries': summaries,
        'declared_length_mismatch_episode_ids': [e['episode_id'] for e in summaries
                                               if not e['declared_length_matches_observed']],
        'scope': 'One complete convenience shard; not a corpus coverage estimate.',
    }


def audit_ac(ac_cache, Example, Forest, Node):
    data = (ac_cache / 'shard0-prefix.tfrecord').read_bytes()
    counts, fields = Counter(), set()
    for record in frames(data, allow_incomplete=True):
        f = Example.FromString(record).features.feature
        fields.update(f.keys())
        pictures, forests = vals(f, 'screenshots'), vals(f, 'accessibility_trees')
        actions, instructions = vals(f, 'actions'), vals(f, 'step_instructions')
        assert len(pictures) == len(forests) == len(actions) + 1
        assert len(actions) == len(instructions)
        counts.update(episodes=1, observations=len(pictures), actions=len(actions))
        for i, (pic, forest) in enumerate(zip(pictures, forests)):
            image = Image.open(io.BytesIO(pic))
            assert image.size == (vals(f, 'screenshot_widths')[i], vals(f, 'screenshot_heights')[i])
            image.verify()
            parsed = Forest.FromString(forest)
            counts['nodes'] += sum(len(w.tree.nodes) for w in parsed.windows)
    return {
        'source': 'Official shard 0; existing 16 MiB compressed prefix independently reparsed',
        'compressed_prefix_sha256': sha(ac_cache / 'shard0-prefix.gz'),
        'decompressed_prefix_sha256': sha(ac_cache / 'shard0-prefix.tfrecord'),
        'counts': dict(counts), 'fields': sorted(fields),
        'crcs_images_alignment_valid': True,
        'node_proto_fields': [f.name for f in Node.DESCRIPTOR.fields],
        'scope': 'Only complete frames; incomplete trailing frame excluded. No coverage claim.',
    }


def extract(path, name, namespace):
    node = next(n for n in ast.parse(path.read_text()).body if getattr(n, 'name', None) == name)
    module = ast.Module(body=[node], type_ignores=[])
    exec(compile(module, str(path), 'exec'), namespace)
    return namespace[name]


def audit_predicates(cache):
    # Use the exact scalar helper AST, substituting NumPy for JAX's abs/equal.
    helper = extract(cache / 'aitw-action-matching.py', '_check_drag_actions_match',
                     {'np': np, 'jnp': np})
    drag_cases = [
        ('right_vs_left', [.5, .2], [.5, .8], [.5, .8], [.5, .2], True),
        ('down_vs_up', [.2, .5], [.8, .5], [.8, .5], [.2, .5], True),
        ('horizontal_vs_vertical', [.5, .2], [.5, .8], [.2, .5], [.8, .5], False),
    ]
    drag_results = []
    for name, a, b, c, d, expected in drag_cases:
        matched = bool(helper(*map(np.array, (a, b, c, d))))
        assert matched == expected
        drag_results.append({'case': name, 'matched': matched})

    namespace = {'abc': abc, 'Any': Any, 'random': random,
                 'device_constants': NS(DT=None), 'interface': NS(AsyncEnv=object)}
    base = extract(cache / 'aw-task-eval.py', 'TaskEval', namespace)
    commands, output = [], ['0']
    def fake_adb(command, controller):
        commands.append(command)
        return NS(generic=NS(output=output[0].encode()))
    namespace.update(task_eval=NS(TaskEval=base), adb_utils=NS(issue_generic_request=fake_adb))
    brightness = extract(cache / 'aw-system.py', '_SystemBrightnessToggle', namespace)
    evaluator_results = []
    for mode, value, expected in [('max', 255, 1), ('max', 254, 0), ('min', 1, 1),
                                  ('min', 0, 0), ('min', 255, 0)]:
        task = brightness({'max_or_min': mode})
        task.initialized = True  # Initialization/device setup NOT exercised.
        output[0] = str(value)
        score = task.is_successful(NS(controller=object()))
        assert score == expected
        evaluator_results.append({'mode': mode, 'fake_adb_value': value, 'score': score})
    return {
        'aitw_drag_helper': {'fixtures': drag_results,
                            'scope': 'Exact function AST; NumPy abs/equal stand in for JAX scalar operations. Full JAX matcher not run.'},
        'android_world_brightness': {'fixtures': evaluator_results, 'commands': commands,
                                    'scope': 'Exact TaskEval and brightness class ASTs, mocked ADB output; initialized flag injected. No device/reset/replay tested.'},
    }


def audit_click(repo):
    sys.path.insert(0, str(repo / 'AI-Agent/src'))
    from ai_agent.runtime.tasks.mvp_01_font_size_increase import MVP_01_TASK_GRAPH as graph
    from ai_agent.runtime.targeting import target_key
    from ai_agent.runtime.types import NormalizedNode, ScreenSnapshot
    from ai_agent.runtime.verification import verify_transition

    node = NormalizedNode(node_id='audit-font', label='Font size', role='slider',
                          bounds=(0, 0, 100, 20), actionable=True, package_name='com.android.settings',
                          value=1.2, window_id=1)
    baseline = ScreenSnapshot('S4_FONT_SIZE_CONTROL', 'before', observation_id='before', captured_at_ms=100)
    after = ScreenSnapshot('S4_FONT_SIZE_CONTROL', 'after', observation_id='after', captured_at_ms=300)
    attempt = {'attempt_id': 'audit', 'event_key': 'synthetic-correlated-event', 'action_at_ms': 200,
               'baseline_observation_id': 'before', 'baseline_value': 1.0,
               'target_key': target_key(node), 'target_window_id': 1}
    fixtures = [
        ('no_correlated_action', {**attempt, 'event_key': None}, node, after, 'no_correlated_action', False),
        ('missing_baseline_value', {**attempt, 'baseline_value': None}, node, after, 'target_value_unobservable', False),
        ('missing_current_value', attempt, replace(node, value=None), after, 'target_value_unobservable', False),
        ('same_target_value_increased', attempt, node, after, 'target_value_increased', True),
        ('brightness_is_not_font', attempt, replace(node, label='Brightness'), after, 'target_value_unobservable', False),
        ('old_observation', attempt, node, baseline, 'observation_precedes_action', False),
    ]
    results = []
    for name, att, target, snap, evidence, completed in fixtures:
        result = verify_transition(graph=graph, previous_state_id='S4_FONT_SIZE_CONTROL',
                                   pending_transition_id='S4_TO_S5_INCREASE_FONT_SIZE',
                                   current_state_id='S4_FONT_SIZE_CONTROL', previous_snapshot=baseline,
                                   current_snapshot=snap, attempt=att, nodes=(target,))
        assert result.evidence == evidence and result.task_completed == completed
        results.append({'fixture': name, 'status': result.status.value,
                        'evidence': result.evidence, 'task_completed': result.task_completed})
    files = ['AI-Agent/src/ai_agent/runtime/verification.py',
             'AI-Agent/src/ai_agent/runtime/targeting.py',
             'AI-Agent/src/ai_agent/runtime/types.py',
             'AI-Agent/src/ai_agent/runtime/tasks/mvp_01_font_size_increase.py']
    return {'fixtures': results, 'source_sha256': {p: sha(repo / p) for p in files},
            'scope': 'Real verifier with synthetic correlated attempts; event correlation, full engine and device integration not exercised.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument('--cache', type=Path, default=Path('/private/tmp/task-graph-revalidation'))
    parser.add_argument('--ac-cache', type=Path, default=Path('/private/tmp/android-control-review'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sys.path.insert(0, str(args.ac_cache / 'protos'))
    from tensorflow.core.example.example_pb2 import Example
    from android_env.proto.a11y.android_accessibility_forest_pb2 import AndroidAccessibilityForest
    from android_env.proto.a11y.android_accessibility_node_info_pb2 import AndroidAccessibilityNodeInfo
    manifest = json.loads((args.cache / 'source-manifest.json').read_text())
    for entry in manifest['sources']:
        assert sha(args.cache / entry['file']) == entry['sha256']
    result = {
        'checked_at': '2026-09-20', 'source_manifest': manifest,
        'dependencies': {p: importlib.metadata.version(p) for p in ['numpy', 'pillow', 'protobuf', 'google-crc32c']},
        'protobuf_source_hashes': {str(p.relative_to(args.ac_cache / 'protos')): sha(p)
                                  for p in sorted((args.ac_cache / 'protos').rglob('*.proto'))},
        'aitw_raw_sample': audit_aitw(args.cache, Example),
        'android_control_raw_sample': audit_ac(args.ac_cache, Example, AndroidAccessibilityForest, AndroidAccessibilityNodeInfo),
        'predicate_probes': audit_predicates(args.cache),
        'click_verifier': audit_click(args.repo),
        'readiness': {'adb_on_path': shutil.which('adb'), 'emulator_on_path': shutil.which('emulator'),
                      'android_world_live_replay_run': False, 'click_dataset_e2e_run': False},
    }
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'aitw_records': result['aitw_raw_sample']['records'],
                      'aitw_episodes': result['aitw_raw_sample']['episodes'],
                      'ac_counts': result['android_control_raw_sample']['counts'],
                      'predicate_probes': result['predicate_probes'], 'click': result['click_verifier']['fixtures']}, indent=2))


if __name__ == '__main__':
    main()
