"""Check a configured classifier using synthetic text; never access user data."""
import argparse
import json
from pathlib import Path
import sys

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent' / 'src'))

from app.services.goal_inputs import GoalInputRequirements
from app.services.goal_llm import LocalGoalLlm


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, help='Load server settings from this local file (optional).')
    args = parser.parse_args(argv)
    if args.env_file is not None:
        if not args.env_file.is_file():
            print('CONFIG_ERROR: env file does not exist.')
            return 2
        from dotenv import load_dotenv
        load_dotenv(args.env_file, override=False)
    try:
        LocalGoalLlm.configuration()
    except ValueError:
        print('CONFIG_ERROR: set SELF_HOSTED_LLM_URL, SELF_HOSTED_LLM_TOKEN and SELF_HOSTED_LLM_MODEL; '
              'use HTTPS for a remote server (HTTP is allowed only on loopback).')
        return 2

    catalog = json.loads((BACKEND / 'app/task_graphs/goal_catalog.json').read_text(encoding='utf-8'))
    candidates = [{key: goal[key] for key in ('goal_type', 'display_name', 'target_app', 'slots')}
                  for goal in catalog['goals'] if goal.get('execution_status') == 'approved']
    requirements = GoalInputRequirements()
    family = requirements.family('settings.font_size.increase')
    if family is None or not {'settings.font_size.increase', 'settings.font_size.decrease'}.issubset(
            {goal['goal_type'] for goal in candidates}):
        print('CATALOG_ERROR: this smoke check requires the approved font-size goals.')
        return 2

    cases = (
        ('increase', '휴대폰 전체 문자가 깨알 같아요. 읽기 편하게 확대해 주세요.', 'increase'),
        ('decrease', '휴대폰 전체 문자가 너무 큼직해요. 더 작게 해주세요.', 'decrease'),
        ('missing_scope', '문자가 깨알 같아요. 읽기 편하게 확대해 주세요.', 'increase'),
    )
    llm, passed = LocalGoalLlm(), 0
    for label, source, direction in cases:
        result = llm.interpret_inputs(source, candidates, family['fields'])
        ok = bool(result and result['goal_type'] == 'settings.font_size.' + direction
                  and result['inputs'].get('direction') == direction)
        if label == 'missing_scope' and result:
            ok = ok and 'scope' not in result['inputs']
        passed += int(ok)
        # Do not print URLs, credentials, prompts, responses or transport exceptions.
        print(f'{label}: {"PASS" if ok else "FAIL"}')
    print(f'{passed}/{len(cases)} synthetic checks passed. This is a connection check, not a quality evaluation.')
    return 0 if passed == len(cases) else 1


if __name__ == '__main__':
    raise SystemExit(main())
