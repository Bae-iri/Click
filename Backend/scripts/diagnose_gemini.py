"""Static Gemini diagnostics. Prints sanitized API errors, never keys or screen data."""
import argparse
import json
from pathlib import Path
import os
import sys
from time import monotonic

BACKEND=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BACKEND))


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
            result={'check':name,'model':model}
            try:
                response=client.request(method,url,json=payload)
                result['http_status']=response.status_code
                result['content_type']=response.headers.get('content-type','')
                if response.headers.get('retry-after'):result['retry_after']=response.headers['retry-after'][:80]
                try:body=response.json()
                except ValueError:body={}
                if response.status_code==200 and name=='model_access':
                    result.update(model_name=body.get('name'),methods=body.get('supportedGenerationMethods'))
                elif response.status_code==200:
                    result['finish_reasons']=[c.get('finishReason') for c in body.get('candidates',[])]
                else:
                    failed=True
                    result.update(provider_error(response,token))
            except httpx.HTTPError as error:
                failed=True
                result['network_error']=type(error).__name__
            result['latency_ms']=round((monotonic()-started)*1000)
            print(json.dumps(result,ensure_ascii=False),flush=True)
    return 1 if failed else 0


if __name__=='__main__':raise SystemExit(main())
