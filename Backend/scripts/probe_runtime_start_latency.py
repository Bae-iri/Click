"""Probe real Jev runtime latency using synthetic or recorded public Settings screens.

This does not operate Android, write the database, or measure the whole HTTP flow.
"""
import argparse
from datetime import datetime, timezone
import json
import logging
import os
from pathlib import Path
import sys
from time import monotonic, time
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'Backend'), str(ROOT / 'AI-Agent/src')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repeats', type=int, default=3, choices=range(1, 21))
    parser.add_argument('--scene', choices=('synthetic', 'recorded'), default='synthetic')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Use a new output file; previous observations must be preserved.')
    from dotenv import dotenv_values
    from app.schemas.agent_turn import AgentCommandRequest
    from app.task_graphs import approved_graphs
    from app.clients.runtime_process import run_runtime
    from app.clients.runtime_work import RuntimeWork
    from app.clients.runtime_timing import PHASES
    values = dotenv_values(ROOT / 'Backend/.env')
    for key in ('TYPESAFE_API_KEY', 'JEV_MODEL', 'JEV_MIN_CONFIDENCE',
                'JEV_SCREEN_JUDGMENT_MODE', 'JEV_CHOICE_TEMPERATURE'):
        if values.get(key):
            os.environ[key] = values[key]
    if not os.environ.get('TYPESAFE_API_KEY'):
        parser.error('Jev is not configured.')
    os.environ.update(AI1_UI_SELECTOR_PROVIDER='jev', CLICK_SETTINGS_EXPERIMENT_ENABLED='0')
    logging.basicConfig(level=logging.INFO, format='%(message)s')
    report = dict(schema_version='click.runtime-start-probe.v1',
                  source='synthetic_public_settings_home' if args.scene == 'synthetic' else 'recorded_public_samsung_screens', real_provider=True,
                  timeout_seconds=20, measures='isolated_runtime_only',
                  physical_device=False, includes_http_api_and_database=False, trials=[])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    recorded = {}
    if args.scene == 'recorded':
        fixture = json.loads((ROOT / 'Backend/tests/fixtures/samsung-public-navigation-20261122.json').read_text())
        recorded = {case['task']: case['tree'] for case in fixture['cases']}
    for repeat in range(args.repeats):
        for key in (('display', 'wifi') if args.scene == 'synthetic' else ('font_size', 'wifi')):
            goal_type = 'settings.navigation.' + key
            graph = approved_graphs()[goal_type]
            package = 'com.android.settings'
            nodes = [dict(node_id='title', text='설정', class_name='android.widget.TextView',
                          bounds_in_screen=[0, 0, 1000, 150], extras={'heading': True})]
            for index, label in enumerate(('디스플레이', '연결')):
                nodes.append(dict(node_id='row'+str(index), text=label,
                                  class_name='android.widget.LinearLayout', clickable=True,
                                  bounds_in_screen=[0, 200+index*150, 1080, 330+index*150]))
            for node in nodes:
                node.update(package_name=package, window_id=1)
            tree = dict(package_name=package, active_window_id=1,
                        screen_width=1080, screen_height=2400, nodes=nodes)
            if recorded:
                tree = dict(recorded[key])
            tree.update(captured_at=datetime.now(timezone.utc).isoformat(),
                        captured_at_ms=int(time()*1000), observation_id=str(uuid4()))
            request = AgentCommandRequest.model_validate(dict(
                contract_version='agent-fe-contract.v0.3', command_type='start_task', expected_version=0,
                request_id=str(uuid4()), session_id=str(uuid4()),
                goal=dict(catalog_version='2.2.0', goal_type=goal_type, target_app='android_settings',
                          slots=graph.definition.required_slots),
                device_context=dict(platform='android', locale='ko-KR', foreground_package=package),
                accessibility_tree=tree))
            row = dict(task=key, repeat=repeat)
            started = monotonic()
            try:
                result = run_runtime(RuntimeWork(request, graph, None), 20)['response']
                selection = result.get('metadata', {}).get('debug', {}).get('ui_selection', {})
                row.update(runtime_status=200, action=result['guide_command'].get('action_type'),
                           selection={k: selection[k] for k in ('status', 'called', 'provider_latency_ms',
                                      'filter_time_ms', 'candidate_count') if k in selection})
            except Exception as error:
                # Do not persist arbitrary provider/exception messages.
                row.update(runtime_status=getattr(error, 'status_code', None), outcome='error')
                failed_phase = (getattr(error, 'headers', None) or {}).get('X-Click-Runtime-Phase')
                if isinstance(failed_phase, str) and failed_phase in PHASES:
                    row['runtime_phase'] = failed_phase
            row['wall_ms'] = round((monotonic()-started)*1000, 3)
            report['trials'].append(row)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
            print(json.dumps(row), flush=True)


if __name__ == '__main__':
    main()
