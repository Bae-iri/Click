"""Opt-in synthetic GPT connectivity check; no real device or user data."""
import argparse
import json
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT),str(ROOT.parent/'AI-Agent/src')]
from app.services.gpt_verifier import GptVerifier
from app.task_graphs import approved_graphs
from ai_agent.runtime.types import AccessibilityNode


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--live',action='store_true')
    parser.add_argument('--env-file',type=Path)
    args=parser.parse_args()
    if not args.live:
        print('DRY_RUN: --live is required; no API requests made.');return 0
    if args.env_file:
        from dotenv import load_dotenv
        load_dotenv(args.env_file,override=False)
    graph=approved_graphs()['settings.font_size.increase']
    nodes=(AccessibilityNode('synthetic-title',package_name='com.android.settings',text='글자 크기',
        class_name='android.widget.TextView',bounds_in_screen=(0,0,400,80)),)
    result=GptVerifier().review_observation(graph=graph,raw_nodes=nodes,reason='target_value_unobservable')
    print(json.dumps({'source':'synthetic_connectivity_only','device_actions':0,'review':result},ensure_ascii=False))
    return 0 if result['status']=='reviewed' else 1


if __name__=='__main__':raise SystemExit(main())
