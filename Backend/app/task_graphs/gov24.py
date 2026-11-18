"""Reviewed bootstrap graphs for public Government24 guidance, separate from native settings."""
import json
from pathlib import Path

DOCUMENT = json.loads((Path(__file__).parents[3] / 'AI-Agent/goal_catalog/gov24_task_graphs.json').read_text(encoding='utf-8'))
GRAPHS = {graph['goal_type']: graph for graph in DOCUMENT['graphs']}


def validate(options):
    if DOCUMENT['schema_version'] != 'gov24-baseline-task-graphs.v1' or len(GRAPHS) != len(DOCUMENT['graphs']):
        raise ValueError('Invalid Government24 baseline registry')
    for graph in GRAPHS.values():
        if (graph['target_app'] != 'gov24' or graph['start_state'] not in graph['states']
                or graph['completion']['scope'] != 'public_screen_only'
                or not graph['states'][graph['completion']['state_id']]['terminal']):
            raise ValueError('Invalid Government24 baseline completion')
        for required in graph['completion']['any_of']:
            if not any(options[key]['group'] == 'marker' for key in required):
                raise ValueError('Independent service title is required')
            if any(options[key]['group'] not in {'marker', 'boundary'} for key in required):
                raise ValueError('Invalid destination evidence')
        for transition in graph['transitions']:
            if (transition['from_state'] not in graph['states'] or transition['to_state'] not in graph['states']
                    or graph['states'][transition['from_state']]['terminal']
                    or options[transition['target_option']]['group'] != 'navigation'
                    or transition['verification'] != 'fresh_public_destination'):
                raise ValueError('Invalid Government24 baseline action')


def choose(graph, page, candidates):
    visible = [edge for edge in graph['transitions'] if edge['from_state'] == page and edge['target_option'] in candidates]
    return min(visible, key=lambda edge: (edge['priority'], edge['id'])) if visible else None
