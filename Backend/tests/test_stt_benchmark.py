import json
import httpx
from app.clients.self_hosted import InferenceEndpoint
from scripts.benchmark_stt import main
from tests.test_transcription import wav_payload,stt_result


def test_benchmark_reports_only_latency_and_accuracy_counts(monkeypatch,tmp_path,capsys):
    monkeypatch.setenv('SELF_HOSTED_STT_URL','http://localhost:8091/v1/transcriptions')
    monkeypatch.setenv('SELF_HOSTED_STT_TOKEN','private-token')
    wav=tmp_path/'private-filename.wav';wav.write_bytes(wav_payload())
    def post(*args,**kwargs):
        return httpx.Response(200,request=httpx.Request('POST','http://localhost:8091/v1/transcriptions'),
            json=stt_result(),headers={'server-timing':'stt_decode;dur=1.00, stt_inference;dur=125.50'})
    monkeypatch.setattr(InferenceEndpoint,'post',post)
    assert main(['--wav',str(wav),'--runs','3','--warmups','0','--expected-text','글자 크기 줄여줘'])==0
    raw=capsys.readouterr().out;body=json.loads(raw)
    assert body['runs']==3 and body['exact_matches']==3
    assert body['worker_inference_p50_ms']==125.5
    assert '글자' not in raw and 'private' not in raw


def test_invalid_audio_is_rejected_before_any_network_request(monkeypatch,tmp_path):
    monkeypatch.setenv('SELF_HOSTED_STT_URL','http://localhost:8091/v1/transcriptions')
    monkeypatch.setenv('SELF_HOSTED_STT_TOKEN','secret')
    wav=tmp_path/'bad.wav';wav.write_bytes(b'invalid')
    monkeypatch.setattr(InferenceEndpoint,'post',lambda *a,**kw: (_ for _ in ()).throw(AssertionError('no network')))
    assert main(['--wav',str(wav)])==1
