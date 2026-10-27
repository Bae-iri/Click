"""Probe released evaluator semantics, not dataset accuracy or live-device success.

Requires the pinned files recorded in alternative-task-graph-audit.json's manifest
and PyYAML. No network, OCR, LLM or device calls are made. Only the named upstream
AST definitions are executed, with logging replaced by a no-op logger.
"""
import argparse
import ast
from collections import defaultdict
from contextlib import redirect_stdout
from dataclasses import asdict
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import types
import warnings


def execute_definitions(path, namespace, names=None):
    tree = ast.parse(path.read_text(), filename=str(path))
    selected = []
    for node in tree.body:
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            if not isinstance(node, ast.ImportFrom) or not node.level:
                selected.append(node)
        elif isinstance(node, (ast.FunctionDef, ast.ClassDef)):
            if names is None or node.name in names:
                selected.append(node)
        elif names is None or (
            isinstance(node, ast.AnnAssign)
            and isinstance(node.target, ast.Name)
            and node.target.id == '_REGISTRY'
        ):
            if isinstance(node, (ast.Assign, ast.AnnAssign)):
                selected.append(node)
    exec(compile(ast.Module(body=selected, type_ignores=[]), str(path), 'exec'), namespace)


class NullLogger:
    def __getattr__(self, name):
        return lambda *args, **kwargs: None


def probe_colorbench(cache):
    source = cache / 'colorbench-parse_result.py'
    ns = {'json': json, 'os': os, 'defaultdict': defaultdict}
    execute_definitions(source, ns, {'get_successful_tasks_by_rule'})
    raw = (cache / 'colorbench-tasks.json').read_text()
    try:
        json.loads(raw)
        parse_error = None
    except json.JSONDecodeError as exc:
        parse_error = {'line': exc.lineno, 'column': exc.colno, 'message': exc.msg}
    repaired, count = re.subn(
        r'("optimal_steps"\s*:\s*\d+)(\s*\n\s*"app_num")', r'\1,\2', raw)
    assert count == 1, 'Inspection repair must remain exactly one comma.'
    tasks = json.loads(repaired)
    task = next(t for t in tasks if t['task_id'] == 1)
    pages = [m['page_node'][0] for m in task['milestone']]
    assert len(set(pages)) == len(pages) == 4
    cases = {
        'milestones_in_list_order_no_actions': [{'screenshot': p} for p in pages],
        'milestones_in_reverse_order_no_actions': [{'screenshot': p} for p in reversed(pages)],
        'milestones_with_explicit_null_actions': [{'screenshot': p, 'action': None} for p in pages],
        'missing_last_milestone': [{'screenshot': p} for p in pages[:-1]],
    }
    results = []
    with tempfile.TemporaryDirectory(prefix='colorbench-semantic-probe-') as tmp:
        for name, trace in cases.items():
            root = Path(tmp) / name
            directory = root / task['query'].replace('/', '_')
            directory.mkdir(parents=True)
            (directory / 'trajectory.json').write_text(json.dumps(trace))
            with redirect_stdout(io.StringIO()):
                completed, rates, _ = ns['get_successful_tasks_by_rule'](
                    str(root), {task['query']: task['milestone']})
            results.append({'case': name, 'input_trace': trace,
                            'completed': task['query'] in completed, 'milestone_rates': rates})
    assert [r['completed'] for r in results] == [True, True, True, False]
    return {'raw_tasks_parse_error': parse_error,
            'inspection_only_inserted_commas': count, 'source_task_id': task['task_id'],
            'results': results,
            'interpretation': 'The aggregation function checks milestone membership, not order, legal transitions, or action causality. Synthetic traces are NOT benchmark executions; trusted graph stepping may enforce other constraints.'}


def probe_mobiflow(cache):
    module = types.ModuleType('_pinned_mobiflow_probe')
    sys.modules[module.__name__] = module
    ns = module.__dict__
    for name in ('get_logger', 'get_frame_logger', 'get_verifier_logger',
                 'get_llm_logger', 'get_condition_logger'):
        ns[name] = lambda *a, **kw: NullLogger()
    for file in ('mobiflow-types.py', 'mobiflow-dag.py', 'mobiflow-loader.py'):
        execute_definitions(cache / file, ns)
    execute_definitions(cache / 'mobiflow-conditions.py', ns, {
        'ConditionChecker', 'UIFlag', 'TextMatch', 'EscalateChecker',
        'register_condition', 'get_checker'})
    execute_definitions(cache / 'mobiflow-verifier.py', ns, {
        '_collect_candidates_path_aware', '_get_path_used_frames',
        '_update_successor_reachability', '_collect_candidates', '_node_needs_ocr',
        '_node_needs_llm', '_min_feasible_index', '_calculate_total_score', 'verify'})
    Node, Cond, Task, Success = (ns[k] for k in ('NodeSpec', 'ConditionSpec', 'TaskSpec', 'SuccessSpec'))

    def node(name, **kw):
        return Node(id=name, condition=Cond('ui_flag', {'key': name, 'equals': True}), **kw)

    chain = Task('synthetic_chain', [node('A'), node('B', deps=['A'])], Success(all_of=['B']))
    and_task = Task('synthetic_and', [node('A'), node('B'), node('C', deps=['A', 'B'])], Success(all_of=['C']))
    or_task = Task('synthetic_or', [node('A', next=['C']), node('B', next=['C']), node('C')], Success(any_of=['C']))
    cases = [
        ('ordered_chain_without_action_metadata', chain, [{'ui': {'A': True}}, {'ui': {'B': True}}]),
        ('reversed_chain', chain, [{'ui': {'B': True}}, {'ui': {'A': True}}]),
        ('missing_chain_precondition', chain, [{'ui': {'B': True}}]),
        ('and_both_preconditions', and_task, [{'ui': {'A': True}}, {'ui': {'B': True}}, {'ui': {'C': True}}]),
        ('and_missing_one_precondition', and_task, [{'ui': {'A': True}}, {'ui': {'C': True}}]),
        ('or_first_alternative', or_task, [{'ui': {'A': True}}, {'ui': {'C': True}}]),
        ('or_second_alternative', or_task, [{'ui': {'B': True}}, {'ui': {'C': True}}]),
        ('chain_with_later_repeated_first_state', chain, [{'ui': {'A': True}}, {'ui': {'B': True}}, {'ui': {'A': True}}]),
    ]
    results = []
    for name, task, frames in cases:
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            result = ns['verify'](frames, task, ns['VerifierOptions']())
        results.append({'case': name, 'synthetic_task': asdict(task), 'input_frames': frames,
                        'ok': result.ok, 'matched': [asdict(x) for x in result.matched],
                        'reason': result.reason, 'total_score': result.total_score})
    assert [r['ok'] for r in results] == [True, False, False, True, False, True, True, False]

    # Run the actual loader/DAG on every downloaded rule, not an imitation parser.
    rules = []
    for path in sorted(cache.glob('mobiflow-rule-*.yaml')):
        record = {'file': path.name}
        try:
            task = ns['load_task'](str(path))
            record['task_id'] = task.task_id
            record['loaded'] = True
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                ns['DAG'](task.nodes)
            record['dag_constructed'] = True
            ids = {n.id for n in task.nodes}
            success = task.success
            record['undefined_success_nodes'] = sorted(
                set((success.any_of or []) + (success.all_of or [])) - ids) if success else []
            record['empty_condition_params'] = [n.id for n in task.nodes if n.condition and not n.condition.params]
        except Exception as exc:
            record['error_type'] = type(exc).__name__
            record['error'] = str(exc)
        rules.append(record)
    return {'results': results, 'released_rule_loader_results': rules,
            'interpretation': 'Exact dependency verifier + exact ui_flag condition with synthetic booleans. No OCR/LLM accuracy claim. Repeated A after B loses an earlier feasible A->B sequence in this nonexclusive ui_flag mode; published escalate rules are not thereby proven to share that outcome.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads((args.cache / 'manifest.json').read_text())
    required = ['colorbench-tasks.json', 'colorbench-parse_result.py', 'mobiflow-types.py',
                'mobiflow-dag.py', 'mobiflow-loader.py', 'mobiflow-verifier.py', 'mobiflow-conditions.py']
    sources = [x for x in manifest if x['file'] in required or x['file'].startswith('mobiflow-rule-')]
    assert set(required) <= {x['file'] for x in sources}
    for item in sources:
        assert hashlib.sha256((args.cache / item['file']).read_bytes()).hexdigest() == item['sha256']
    refetch = []
    for original, fresh in [('colorbench-tasks.json', 'colorbench-tasks-refetch.json'),
                            ('mobiflow-verifier.py', 'mobiflow-verifier-refetch.py')]:
        fresh_path = args.cache / fresh
        same = fresh_path.read_bytes() == (args.cache / original).read_bytes() if fresh_path.exists() else None
        assert same is not False
        refetch.append({'source': original, 'fresh_copy_exists': fresh_path.exists(), 'bytes_identical': same})
    result = {'checked_at': '2026-09-20', 'source_manifest': sources,
              'fresh_download_comparison': refetch,
              'scope': '12 synthetic function-level probes plus actual loader checks. No benchmark, model, emulator, device or OCR/LLM execution.',
              'colorbench': probe_colorbench(args.cache), 'mobiflow': probe_mobiflow(args.cache)}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'output': str(args.output),
                      'colorbench': {r['case']: r['completed'] for r in result['colorbench']['results']},
                      'mobiflow': {r['case']: r['ok'] for r in result['mobiflow']['results']},
                      'rules_checked': len(result['mobiflow']['released_rule_loader_results'])}, indent=2))


if __name__ == '__main__':
    main()
