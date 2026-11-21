"""One minimal live Settings planning call; no credentials or raw screen text printed."""
import argparse
import json
from pathlib import Path
import sys

BACKEND=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BACKEND))
sys.path.insert(0,str(BACKEND.parent/'AI-Agent/src'))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file',type=Path,default=BACKEND/'instance/settings-experiment/.env')
    parser.add_argument('--model',help='Run with qwen/qwen3.8-flash or a specific Gemini model')
    parser.add_argument('--timeout',type=float,default=8,help='Diagnostic deadline, 1–60 seconds; app remains at 8 seconds')
    args=parser.parse_args()
    from dotenv import load_dotenv
    if not args.env_file.is_file():parser.error('Prepare the experiment environment first')
    if not 1 <= args.timeout <= 60:parser.error('--timeout must be between 1 and 60')
    load_dotenv(args.env_file,override=True)
    from app.services.settings_planner import SettingsPlanner,catalogue,navigation_key,select_experiment_model
    if args.model:select_experiment_model(args.model)
    from app.task_graphs import approved_graphs
    graph=approved_graphs()['settings.font_size.increase']
    targets,_=catalogue(graph)
    plan,diagnostic=SettingsPlanner(timeout=args.timeout).plan(goal=graph.definition.goal_type,targets=targets,
        current_page='설정 홈',visible_targets=[navigation_key('S2_DISPLAY_MENU')],completed=[],failures=[])
    print(json.dumps({'plan':plan,'diagnostic':diagnostic},ensure_ascii=False))
    return 0 if plan else 1


if __name__=='__main__':raise SystemExit(main())
