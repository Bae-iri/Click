import httpx

from scripts.check_stt import main
from app.clients.self_hosted import InferenceEndpoint
from tests.test_transcription import wav_payload, stt_result


def test_config_only_is_not_reported_as_inference_success(monkeypatch, capsys):
    monkeypatch.setenv('SELF_HOSTED_STT_URL', 'http://127.0.0.1:8091/v1/transcriptions')
    monkeypatch.setenv('SELF_HOSTED_STT_TOKEN', 'secret')
    assert main([]) == 0
    assert 'CONFIGURED_ONLY' in capsys.readouterr().out


def test_api_check_validates_audio_before_sending(monkeypatch, tmp_path):
    monkeypatch.setenv('STT_CHECK_ACCESS_TOKEN', 'secret')
    wav = tmp_path / 'bad.wav'
    wav.write_bytes(b'invalid')
    monkeypatch.setattr(InferenceEndpoint, 'post', lambda *a, **kw: (_ for _ in ()).throw(AssertionError('network')))
    assert main(['--api-url', 'https://example.com', '--wav', str(wav)]) == 2


def test_real_request_contract_and_no_transcript_or_token_output(monkeypatch, tmp_path, capsys):
    monkeypatch.setenv('STT_CHECK_ACCESS_TOKEN', 'secret-login-token')
    wav = tmp_path / 'test.wav'
    wav.write_bytes(wav_payload())
    def post(endpoint, payload, **kwargs):
        assert endpoint.url == 'https://example.com/api/v1/speech/transcriptions'
        assert endpoint.token == 'secret-login-token'
        assert payload['language'] == 'ko'
        return httpx.Response(200, json=stt_result())
    monkeypatch.setattr(InferenceEndpoint, 'post', post)
    assert main(['--api-url', 'https://example.com', '--wav', str(wav), '--expected-text', '글자 크기 줄여줘']) == 0
    out = capsys.readouterr().out
    assert 'PASS' in out and '글자' not in out and 'secret-login-token' not in out


def test_wrong_provider_is_failure(monkeypatch, tmp_path, capsys):
    monkeypatch.setenv('STT_CHECK_ACCESS_TOKEN', 'secret')
    wav = tmp_path / 'test.wav'
    wav.write_bytes(wav_payload())
    monkeypatch.setattr(InferenceEndpoint, 'post', lambda *a, **kw: httpx.Response(200,
        json={'text': 'private', 'provider': 'openai', 'model': 'gpt-transcribe'}))
    assert main(['--api-url', 'https://example.com', '--wav', str(wav)]) == 1
    assert 'private' not in capsys.readouterr().out
