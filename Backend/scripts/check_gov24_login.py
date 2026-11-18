"""Check Jev login choices using public synthetic preferences, never real credentials."""
import argparse,json,sys,time
from pathlib import Path
BACKEND=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(BACKEND),str(BACKEND.parent/'AI-Agent/src')]
from dotenv import load_dotenv
from app.services.public_guide_jev import JevPublicGuideSelector
from app.services.public_guide_policy import OPTIONS

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file',type=Path)
    parser.add_argument('--live',action='store_true',help='Call the configured Jev API with public sample preferences')
    args=parser.parse_args()
    if args.env_file:load_dotenv(args.env_file,override=True)
    methods=['method_mobile_id','method_simple','method_joint','method_financial']
    providers=[k for k in OPTIONS if k.startswith('provider_')]
    cases=[('provider_implies_simple','카카오로 로그인할래',methods,'choose_method','method_simple'),
           ('financial_method','금융인증서로 할래',methods,'choose_method','method_financial'),
           ('provider_preference','네이버 인증서 있어',providers,'choose_provider','provider_naver'),
           ('negative_preference','카카오 말고 네이버로 할래',providers,'choose_provider','provider_naver'),
           ('uncertain_preference','모르겠어',methods,'choose_method',None)]
    report={'schema_version':'gov24-jev-check.v1','source_kind':'synthetic','device_authentication':'NOT_RUN','live':args.live}
    if not args.live:
        print(json.dumps({**report,'status':'NOT_RUN','cases':len(cases)},ensure_ascii=False));return 0
    selector=JevPublicGuideSelector()
    try:_,model,minimum=selector.configuration()
    except ValueError:
        print(json.dumps({**report,'status':'NOT_CONFIGURED'},ensure_ascii=False));return 1
    results=[]
    for name,answer,offered,subgoal,expected in cases:
        at=time.monotonic();selected=selector.choose(answer,offered,subgoal)
        results.append({'case':name,'selected':selected,'expected':expected,'passed':selected==expected,
            'elapsed_ms':round((time.monotonic()-at)*1000)})
    passed=all(r['passed'] for r in results)
    print(json.dumps({**report,'model':model,'minimum_confidence':minimum,'status':'PASS'if passed else'NEEDS_REVIEW','results':results},ensure_ascii=False,indent=2))
    return 0 if passed else 1
if __name__=='__main__':raise SystemExit(main())
