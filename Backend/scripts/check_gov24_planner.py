"""One real Gemini planning call; prints canonical plan and metadata, never credentials."""
import argparse
import json
import os
import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent' / 'src'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=BACKEND / '.env')
    parser.add_argument('--check-model', action='store_true', help='Check model access without generating a plan')
    parser.add_argument('--ping', action='store_true', help='Minimal generation request to diagnose latency')
    args = parser.parse_args()
    from dotenv import load_dotenv
    load_dotenv(args.env_file, override=True)
    from app.services.gov24_planner import GeminiGovernmentPlanner
    if args.ping:
        import httpx
        from app.services.gov24_planner import MODEL
        import time
        started = time.monotonic()
        try:
            response = httpx.post(f'https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent',
                headers={'x-goog-api-key': os.getenv('GEMINI_API_KEY', '')},
                json={'contents': [{'parts': [{'text': 'Return OK.'}]}],
                      'generationConfig': {'maxOutputTokens': 128, 'thinkingConfig': {'thinkingLevel': 'low'}}},
                timeout=30, trust_env=False)
            print(json.dumps({'model': MODEL, 'http_status': response.status_code,
                              'latency_ms': round((time.monotonic() - started) * 1000)}))
            return 0 if response.status_code == 200 else 1
        except httpx.HTTPError as error:
            print(json.dumps({'model': MODEL, 'error_type': type(error).__name__}))
            return 1
    if args.check_model:
        import httpx
        from app.services.gov24_planner import MODEL
        try:
            response = httpx.get(f'https://generativelanguage.googleapis.com/v1beta/models/{MODEL}',
                headers={'x-goog-api-key': os.getenv('GEMINI_API_KEY', '')}, timeout=8, trust_env=False)
            print(json.dumps({'model': MODEL, 'http_status': response.status_code}))
            return 0 if response.status_code == 200 else 1
        except httpx.HTTPError as error:
            print(json.dumps({'model': MODEL, 'error_type': type(error).__name__}))
            return 1
    plan, diagnostic = GeminiGovernmentPlanner().plan('gov24.resident_copy.open', 'public_menu',
        ['nav_resident', 'nav_services'], [], [])
    print(json.dumps({'plan': plan, 'diagnostic': diagnostic}, ensure_ascii=False))
    return 0 if plan else 1


if __name__ == '__main__':
    raise SystemExit(main())
