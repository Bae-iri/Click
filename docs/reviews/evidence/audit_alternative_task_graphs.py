"""Read-only structural audit of downloaded public graph artifacts.

Run with Python + PyYAML, passing --cache and --output. Sources and hashes are
recorded in cache/manifest.json. This does not execute a benchmark or an agent.
"""
import argparse
from collections import Counter, defaultdict, deque
import hashlib
import json
from pathlib import Path
import re

import yaml


def graph_check(nodes, edges):
    nodes = set(map(str, nodes))
    edges = {(str(a), str(b)) for a, b in edges}
    missing = sorted({v for edge in edges for v in edge} - nodes)
    counts = dict.fromkeys(nodes, 0)
    out = defaultdict(set)
    for a, b in edges:
        if a in nodes and b in nodes:
            out[a].add(b)
            counts[b] += 1
    queue = deque(n for n in nodes if counts[n] == 0)
    visited = 0
    while queue:
        n = queue.popleft()
        visited += 1
        for nxt in out[n]:
            counts[nxt] -= 1
            if counts[nxt] == 0:
                queue.append(nxt)
    return {'nodes': len(nodes), 'distinct_edges': len(edges),
            'missing_node_references': missing,
            'acyclic_among_defined_nodes': visited == len(nodes)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    p = args.cache
    load = lambda name: json.loads((p / name).read_text())
    manifest = load('manifest.json')
    for source in manifest:
        if 'sha256' in source:
            assert hashlib.sha256((p / source['file']).read_bytes()).hexdigest() == source['sha256']
    result = {'checked_at': '2026-09-20', 'scope': 'Structural artifact audit, not semantic gold certification or end-to-end replay',
              'source_manifest': manifest}

    graph = load('colorbench-graph.json')
    raw = (p / 'colorbench-tasks.json').read_text()
    try:
        json.loads(raw)
        parse_error = None
    except json.JSONDecodeError as error:
        parse_error = {'message': error.msg, 'line': error.lineno, 'column': error.colno}
    # Only an in-memory, recorded syntax repair for inspecting task references.
    # Never present this derived copy as the unmodified source.
    fixed, repairs = re.subn(r'("optimal_steps"\s*:\s*\d+)(\s*\n\s*"app_num")', r'\1,\2', raw)
    tasks = json.loads(fixed)
    edges = [(src, dst) for src, dests in graph.items() for dst in dests]
    # Destination-only entries are legitimate implicit sinks in this adjacency format.
    graph_nodes = set(graph) | {dst for _, dst in edges}
    actions = [act for dests in graph.values() for acts in dests.values() for act in acts]
    task_missing = []
    for task in tasks:
        missing = sorted({n for m in task['milestone'] for n in m.get('page_node', [])} - graph_nodes)
        if missing:
            task_missing.append({'task_id': task['task_id'], 'missing_milestone_nodes': missing})
    result['colorbench'] = {
        **graph_check(graph_nodes, edges), 'adjacency_source_entries': len(graph),
        'destination_only_nodes': len(graph_nodes - graph.keys()),
        'raw_tasks_json_error': parse_error,
        'inspection_only_syntax_repair': {'inserted_commas_after_optimal_steps': repairs,
                                         'original_source_unchanged': True},
        'derived_task_count': len(tasks), 'app_num_counts': dict(Counter(t['app_num'] for t in tasks)),
        'milestones': sum(len(t['milestone']) for t in tasks),
        'milestones_without_page_node': sum('page_node' not in m for t in tasks for m in t['milestone']),
        'task_missing_milestone_references': task_missing,
        'action_records': len(actions), 'action_types': dict(Counter(a.get('action_type') for a in actions)),
        'edges_without_action_annotations': sum(not acts for dests in graph.values() for acts in dests.values()),
        'screenshots_downloaded': False, 'benchmark_executed': False,
    }

    rules, errors, condition_types, app_ids = [], [], Counter(), set()
    for f in sorted(p.glob('mobiflow-rule-*.yaml')):
        try:
            d = yaml.safe_load(f.read_text())
            nodes = d.get('nodes', [])
            ids = [n['id'] for n in nodes]
            edges = [(dep, n['id']) for n in nodes for dep in (n.get('deps') or [])]
            edges += [(n['id'], nxt) for n in nodes for nxt in (n.get('next') or [])]
            success = d.get('success') or {}
            targets = (success.get('any_of') or []) + (success.get('all_of') or [])
            if d.get('app_id'):
                app_ids.add(d['app_id'])
            for n in nodes:
                condition_types[(n.get('condition') or {}).get('type', 'none')] += 1
            rules.append({'file': f.name, 'task_id': d.get('task_id'), 'app_id': d.get('app_id'),
                          **graph_check(ids, edges), 'duplicate_node_ids': len(ids) - len(set(ids)),
                          'success': success, 'undefined_success_nodes': sorted(set(targets)-set(ids)),
                          'or_branch_nodes': sum(len(n.get('next') or []) > 1 for n in nodes),
                          'and_dependency_nodes': sum(len(n.get('deps') or []) > 1 for n in nodes),
                          'condition_keys_ignored_by_loader': [
                              {'node': n['id'], 'keys': sorted(set(n['condition']) - {'type', 'params'})}
                              for n in nodes if n.get('condition') and set(n['condition']) - {'type', 'params'}]})
        except Exception as error:
            errors.append({'file': f.name, 'error': str(error)})
    result['mobiagent_mobiflow'] = {'rule_files': len(rules)+len(errors), 'parsed_rules': len(rules),
                                   'errors': errors, 'app_ids': sorted(app_ids),
                                   'condition_types': dict(condition_types), 'rules': rules,
                                   'benchmark_executed': False}

    utg = yaml.safe_load((p / 'droidtask-calendar-utg.yaml').read_text())
    result['droidtask'] = {'file': 'droidtask-calendar-utg.yaml', 'fields': sorted(utg),
                          'records': len(utg['records']), 'declared_step_num': utg['step_num'],
                          'record_fields': sorted({k for r in utg['records'] for k in r}),
                          'scope': 'Only repository calendar exploration UTG; full Drive task/state archive not downloaded'}

    cooking = []
    for f in sorted(p.glob('dtgl-*.json')):
        d = json.loads(f.read_text())
        if isinstance(d, dict) and 'steps' in d and 'edges' in d:
            cooking.append({'file': f.name, **graph_check(d['steps'].keys(), d['edges'])})
    result['captaincook4d_dtgl'] = {'ground_truth_graph_files': len(cooking), 'graphs': cooking,
                                  'scope': 'Procedural cooking graphs, not Android UI graphs'}

    rows = [json.loads(line) for line in (p / 'taskbench-data.json').read_text().splitlines() if line.strip()]
    toolgraph = load('taskbench-graph_desc.json')
    result['taskbench'] = {'domain': 'DailyLife APIs', 'rows': len(rows),
                           'row_fields': sorted(rows[0]), 'types': dict(Counter(r['type'] for r in rows)),
                           'tool_library_nodes': len(toolgraph['nodes']), 'tool_library_links': len(toolgraph['links']),
                           'scope': 'Per-example task_nodes/task_links exist; synthetic API planning benchmark, not Android UI'}
    result['mobilebench_v2'] = {name: {'rows': len(load('mobilebench-'+name)),
                                      'fields': sorted(load('mobilebench-'+name)[0])}
                               for name in ['simple_tasks_sample.json', 'complex_tasks_sample.json']}
    result['mobilebench_v2']['scope'] = 'Only released task samples; Mobile3M graph archives not downloaded or joined'
    result['mobile3m'] = {'files_listed': [x['rfilename'] for x in load('mobile3m-api.json')['siblings']],
                          'scope': 'Repository listing and README only; full graph data not parsed'}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({
        'colorbench': result['colorbench'],
        'mobiflow': {k:v for k,v in result['mobiagent_mobiflow'].items() if k!='rules'},
        'mobiflow_rules_with_reference_issues': [r['file'] for r in rules if r['missing_node_references'] or r['undefined_success_nodes']],
        'mobiflow_rules_with_ignored_condition_fields': [r['file'] for r in rules if r['condition_keys_ignored_by_loader']],
        'cooking_graphs': len(cooking),
        'cooking_graphs_structurally_valid': all(r['acyclic_among_defined_nodes'] and not r['missing_node_references'] for r in cooking),
        'taskbench': result['taskbench'],
    }, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
