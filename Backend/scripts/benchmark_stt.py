"""Measure a supplied test WAV against our private worker, without logging audio or transcripts."""
import argparse
import base64
import json
from math import ceil
from pathlib import Path
import re
import statistics
import sys
from time import perf_counter

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
from app.clients.self_hosted import InferenceEndpoint
from app.schemas.transcription import STT_MODEL, TranscriptionRequest
from app.services.audio_validation import decode_pcm_wav


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file',type=Path)
    parser.add_argument('--wav',type=Path,required=True)
    parser.add_argument('--expected-text')
    parser.add_argument('--runs',type=int,default=5)
    parser.add_argument('--warmups',type=int,default=1)
    args=parser.parse_args(argv)
    if not 1<=args.runs<=30 or not 0<=args.warmups<=3:
        parser.error('runs must be 1..30 and warmups 0..3')
    try:
        if args.env_file:
            if not args.env_file.is_file(): raise ValueError()
            from dotenv import load_dotenv
            load_dotenv(args.env_file,override=False)
        endpoint=InferenceEndpoint.from_env('SELF_HOSTED_STT')
        request=TranscriptionRequest(audio_base64=base64.b64encode(args.wav.read_bytes()).decode())
        pcm=decode_pcm_wav(request.audio_base64,request.mime_type)
        elapsed=[];inference=[];matches=0
        for index in range(args.warmups+args.runs):
            started=perf_counter()
            response=endpoint.post(request.model_dump(),timeout=30.0)
            duration=(perf_counter()-started)*1000
            response.raise_for_status()
            body=response.json();text=body['text']
            if (body.get('provider')!='self_hosted' or body.get('model')!=STT_MODEL
                    or not isinstance(text,str) or not text.strip() or len(text)>4000): raise ValueError()
            if index<args.warmups: continue
            elapsed.append(duration)
            timing=re.search(r'(?:^|,\s*)stt_inference;dur=([0-9]+(?:\.[0-9]+)?)',response.headers.get('server-timing',''))
            if timing: inference.append(float(timing.group(1)))
            if args.expected_text is not None and ''.join(text.split())==''.join(args.expected_text.split()):matches+=1
        ordered=sorted(elapsed)
        print(json.dumps({'runs':args.runs,'warmups':args.warmups,'audio_ms':round(len(pcm)/32,2),
            'upload_to_result_p50_ms':round(statistics.median(elapsed),2),
            'upload_to_result_p95_ms':round(ordered[ceil(.95*len(ordered))-1],2),
            'worker_inference_p50_ms':round(statistics.median(inference),2) if inference else None,
            'exact_matches':matches if args.expected_text is not None else None},ensure_ascii=False))
        return 0
    except Exception:
        print('STT benchmark failed. Check private worker configuration and the test WAV; payload omitted.',file=sys.stderr)
        return 1

if __name__=='__main__':raise SystemExit(main())
