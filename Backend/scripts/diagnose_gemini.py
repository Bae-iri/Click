"""Static Gemini diagnostics. Prints sanitized API errors, never keys or screen data."""
import argparse
import json
from pathlib import Path
import os
import re
import sys
from time import monotonic

BACKEND=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BACKEND))


def generation_result(body, *, structured=False):
    """A 200 response proves neither a completed generation nor a valid plan."""
    candidates = body.get('candidates', []) if isinstance(body, dict) else []
    if not isinstance(candidates, list):
        candidates = []
    finishes = [c.get('finishReason') for c in candidates if isinstance(c, dict)]
    result = {'finish_reasons': [f for f in finishes if isinstance(f, str) and f in {
        'STOP', 'MAX_TOKENS', 'SAFETY', 'RECITATION', 'OTHER', 'BLOCKLIST',
        'PROHIBITED_CONTENT', 'SPII', 'MALFORMED_FUNCTION_CALL', 'IMAGE_SAFETY'}],
        'success': False}
    served = body.get('modelVersion') if isinstance(body, dict) else None
    if isinstance(served, str) and re.fullmatch(r'[a-zA-Z0-9._-]{1,160}', served):
        result['served_model'] = served
    if len(candidates) != 1 or finishes != ['STOP']:
        return result
    content = candidates[0].get('content')
    parts = content.get('parts') if isinstance(content, dict) else None
    if not isinstance(parts, list):
        return result
    output = ''.join(p['text'] for p in parts if isinstance(p, dict)
                     and isinstance(p.get('text'), str) and not p.get('thought')).strip()
    result['output_characters'] = len(output)
    result['success'] = bool(output)
    if structured:
        from app.services.goal_llm import _unique_object
        try:
            result['schema_valid'] = json.loads(output, object_pairs_hook=_unique_object) == {
                'subgoals': ['adjust_setting']}
        except (ValueError, TypeError):
            result['schema_valid'] = False
        result['success'] = result['success'] and result['schema_valid']
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file',type=Path,default=BACKEND/'instance/settings-experiment/.env')
    parser.add_argument('--model',help='Explicit model override, no automatic fallback')
    parser.add_argument('--timeout',type=float,default=12)
    args=parser.parse_args()
    from dotenv import load_dotenv
    import httpx
    load_dotenv(args.env_file,override=True)
    token=os.getenv('GEMINI_API_KEY','').strip()
    if not token:parser.error('GEMINI_API_KEY is missing')
    from app.services.settings_planner import model_name, provider_error
    if args.model:os.environ['GEMINI_SETTINGS_MODEL']=args.model
    if not 1 <= args.timeout <= 60:parser.error('--timeout must be between 1 and 60')
    model=model_name()
    endpoint=f'https://generativelanguage.googleapis.com/v1beta/models/{model}'
    structured={'contents':[{'parts':[{'text':'Return a JSON object with subgoals set to ["adjust_setting"].'}]}],
        'generationConfig':{'maxOutputTokens':1024,'thinkingConfig':(
            {'thinkingBudget':0} if model.startswith('gemini-2.5-') else {'thinkingLevel':'low'}),
            'responseMimeType':'application/json','responseJsonSchema':{'type':'object',
                'properties':{'subgoals':{'type':'array','items':{'type':'string','enum':['adjust_setting']}}},
                'required':['subgoals'],'additionalProperties':False}}}
    checks=[('model_access','GET',endpoint,None),('minimal_generation','POST',endpoint+':generateContent',
        {'contents':[{'parts':[{'text':'Return OK.'}]}],'generationConfig':{'maxOutputTokens':64}}),
        ('structured_generation','POST',endpoint+':generateContent',structured)]
    failed=False
    with httpx.Client(timeout=httpx.Timeout(args.timeout,connect=3),trust_env=False,follow_redirects=False,
                      headers={'x-goog-api-key':token}) as client:
        for name,method,url,payload in checks:
            started=monotonic()
            result={'check':name,'model':model,'called':True,'success':False}
            try:
                response=client.request(method,url,json=payload)
                result['http_status']=response.status_code
                result['content_type']=response.headers.get('content-type','')
                if response.headers.get('retry-after'):result['retry_after']=response.headers['retry-after'][:80]
                try:body=response.json()
                except ValueError:body={}
                if not isinstance(body, dict):body={}
                if response.status_code==200 and name=='model_access':
                    result.update(model_name=body.get('name'),methods=body.get('supportedGenerationMethods'))
                    result['success'] = (body.get('name') == 'models/' + model
                        and 'generateContent' in (body.get('supportedGenerationMethods') or []))
                elif response.status_code==200:
                    result.update(generation_result(body, structured=name=='structured_generation'))
                else:
                    failed=True
                    result.update(provider_error(response,token))
            except httpx.HTTPError as error:
                result['network_error']=type(error).__name__
            failed = failed or not result['success']
            result['latency_ms']=round((monotonic()-started)*1000)
            print(json.dumps(result,ensure_ascii=False),flush=True)
    return 1 if failed else 0


if __name__=='__main__':raise SystemExit(main())
