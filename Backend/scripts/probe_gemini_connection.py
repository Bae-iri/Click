"""Bounded live Gemini transport probes using static, non-private inputs.

Credentials and raw response bodies are never printed. No automatic retries.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
from time import monotonic

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent/src'))


def error_details(body):
    from app.services.settings_planner import provider_error
    import httpx
    error = body.get('error', {}) if isinstance(body, dict) else {}
    code = error.get('code', 0) if isinstance(error, dict) else 0
    info = provider_error(httpx.Response(code if type(code) is int and code >= 100 else 500, json=body), '')
    quotas = []
    for detail in error.get('details', []) if isinstance(error, dict) else []:
        if not isinstance(detail, dict):continue
        if detail.get('@type', '').endswith('/google.rpc.QuotaFailure'):
            for violation in detail.get('violations', []):
                if not isinstance(violation, dict):continue
                row = {}
                for key in ('quotaMetric', 'quotaId', 'quotaValue'):
                    value = str(violation.get(key, ''))
                    if re.fullmatch(r'[a-zA-Z0-9_./-]{1,200}', value):row[key] = value
                if row:quotas.append(row)
    if quotas:info['quotas'] = quotas[:10]
    return info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=BACKEND/'instance/settings-experiment/.env')
    parser.add_argument('--model', default='gemini-3.8-flash')
    parser.add_argument('--api', choices=('models', 'generate', 'stream', 'interactions'), default='models')
    parser.add_argument('--shape', choices=('minimal', 'json', 'schema'), default='minimal')
    parser.add_argument('--timeout', type=float, default=15)
    parser.add_argument('--api-version', choices=('v1', 'v1beta'), default='v1beta')
    parser.add_argument('--thinking', choices=('default','minimal','low'), default='default')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 60:parser.error('--timeout must be 1–60 seconds')
    if not re.fullmatch(r'gemini-[a-zA-Z0-9.-]{1,100}', args.model):parser.error('Invalid model name')
    if args.model.startswith('gemini-3.8-flash') and args.thinking == 'minimal':
        parser.error('Gemini 3.8 Flash supports low/medium/high thinking; minimal is not supported')
    from dotenv import load_dotenv
    import httpx
    if not args.env_file.is_file():parser.error('Missing environment file')
    load_dotenv(args.env_file, override=True)
    key = os.getenv('GEMINI_API_KEY', '').strip()
    if not key:parser.error('GEMINI_API_KEY is missing')
    base = f'https://generativelanguage.googleapis.com/{args.api_version}'
    schema = {'type':'object', 'properties':{'subgoals':{'type':'array', 'items':{'type':'string',
        'enum':['adjust_setting']}}}, 'required':['subgoals'], 'additionalProperties':False}
    prompt = 'Reply with only OK.' if args.shape == 'minimal' else 'Return JSON only: {"subgoals":["adjust_setting"]}'
    headers = {'x-goog-api-key':key}
    payload = None
    if args.api == 'models':
        method, url = 'GET', base + '/models?pageSize=1000'
    elif args.api in {'generate','stream'}:
        suffix = ':generateContent' if args.api == 'generate' else ':streamGenerateContent?alt=sse'
        method, url = 'POST', base + '/models/' + args.model + suffix
        config = {'maxOutputTokens':1024}
        if args.thinking != 'default':config['thinkingConfig'] = {'thinkingLevel':args.thinking}
        if args.shape != 'minimal':config['responseMimeType'] = 'application/json'
        if args.shape == 'schema':config['responseJsonSchema'] = schema
        payload = {'contents':[{'role':'user','parts':[{'text':prompt}]}], 'generationConfig':config}
    else:
        method, url = 'POST', base + '/interactions'
        headers['Api-Revision'] = '2026-05-20'
        payload = {'model':args.model, 'input':prompt, 'store':False,
            'generation_config':{'max_output_tokens':1024}}
        if args.thinking != 'default':payload['generation_config']['thinking_level'] = args.thinking
        if args.shape != 'minimal':payload['response_format'] = {'type':'text','mime_type':'application/json'}
        if args.shape == 'schema':payload['response_format']['schema'] = schema
    from datetime import datetime, timezone
    result = dict(observed_at_utc=datetime.now(timezone.utc).isoformat(),
        api=args.api, model=args.model, shape=args.shape, thinking=args.thinking,
        api_version=args.api_version, success=False)
    started = monotonic()
    try:
        with httpx.Client(headers=headers, timeout=httpx.Timeout(args.timeout, connect=3),
                trust_env=False, follow_redirects=False) as client:
            with client.stream(method, url, json=payload) as response:
                result['http_status'] = response.status_code
                result['headers_ms'] = round((monotonic()-started)*1000)
                result['content_type'] = response.headers.get('content-type', '')[:100]
                chunks = bytearray()
                for chunk in response.iter_bytes():
                    if monotonic()-started > args.timeout:raise TimeoutError()
                    chunks.extend(chunk)
                    if len(chunks)>262144:raise ValueError('Oversized response')
                if args.api == 'stream' and response.status_code == 200:
                    bodies = [json.loads(line[6:]) for line in chunks.decode().splitlines() if line.startswith('data: ') and line[6:]!='[DONE]']
                else:bodies = [json.loads(chunks)]
                if response.status_code != 200:
                    result.update(error_details(bodies[0]))
                    result['http_status'] = response.status_code
                elif args.api == 'models':
                    result['models'] = [{'name':m['name'].removeprefix('models/'),
                        'methods':m.get('supportedGenerationMethods',[])} for m in bodies[0].get('models',[])
                        if m.get('name','').startswith('models/gemini-')]
                    result['has_next_page'] = bool(bodies[0].get('nextPageToken'))
                    result['success'] = bool(result['models'])
                else:
                    texts, finishes = [], []
                    for body in bodies:
                        if body.get('error'):
                            result.update(error_details(body))
                            break
                        if body.get('modelVersion'):result['served_model'] = body['modelVersion']
                        for candidate in body.get('candidates',[]):
                            if candidate.get('finishReason'):finishes.append(candidate['finishReason'])
                            texts.extend(p['text'] for p in candidate.get('content',{}).get('parts',[]) if 'text' in p and not p.get('thought'))
                        for step in body.get('steps',[]):
                            if step.get('type') == 'model_output':
                                texts.extend(c['text'] for c in step.get('content',[]) if c.get('type')=='text')
                        if body.get('status'):result['interaction_status'] = body['status']
                        usage = body.get('usageMetadata', {})
                        if usage:result['usage'] = {k:usage[k] for k in ('promptTokenCount','candidatesTokenCount','thoughtsTokenCount') if k in usage}
                    output = ''.join(texts)
                    result['finish_reasons'] = finishes
                    result['output_characters'] = len(output)
                    result['success'] = bool(output) and (not finishes or finishes[-1]=='STOP')
                    if args.shape != 'minimal':
                        try:result['schema_valid'] = json.loads(output)=={'subgoals':['adjust_setting']}
                        except ValueError:result['schema_valid'] = False
                        result['success'] = result['success'] and result['schema_valid']
    except (httpx.HTTPError, TimeoutError, ValueError) as error:
        result.update(error_type=type(error).__name__)
    result['latency_ms'] = round((monotonic()-started)*1000)
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False),flush=True)
    return 0 if result['success'] else 1


if __name__ == '__main__':raise SystemExit(main())
