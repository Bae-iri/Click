import httpx
import pytest

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


def test_env_file_config_without_inference_preserves_environment(monkeypatch, tmp_path, capsys):
    monkeypatch.delenv('SELF_HOSTED_STT_URL', raising=False)
    monkeypatch.setenv('SELF_HOSTED_STT_TOKEN', 'existing-secret')
    env = tmp_path / '.env'
    env.write_text('SELF_HOSTED_STT_URL=http://127.0.0.1:8091/v1/transcriptions\nSELF_HOSTED_STT_TOKEN=file-secret\n')
    monkeypatch.setattr(InferenceEndpoint, 'post', lambda *a, **kw: pytest.fail('No WAV means no inference'))
    assert main(['--env-file', str(env)]) == 0
    import os
    assert os.environ['SELF_HOSTED_STT_TOKEN'] == 'existing-secret'
    assert 'CONFIGURED_ONLY' in capsys.readouterr().out


@pytest.mark.parametrize('url,expected', [('http://127.0.0.1:8000', 0), ('http://localhost:8000', 0),
    ('http://[::1]:8000', 0), ('http://example.com', 2), ('http://localhost.evil.test', 2)])
def test_api_check_accepts_http_only_on_loopback(monkeypatch, url, expected):
    monkeypatch.setenv('STT_CHECK_ACCESS_TOKEN', 'test-token')
    assert main(['--api-url', url]) == expected


@pytest.mark.parametrize('api', [False, True])
def test_no_speech_is_distinct_from_outage(monkeypatch, tmp_path, capsys, api):
    monkeypatch.setenv('STT_CHECK_ACCESS_TOKEN', 'test-token')
    monkeypatch.setenv('SELF_HOSTED_STT_URL', 'http://127.0.0.1:8091/v1/transcriptions')
    monkeypatch.setenv('SELF_HOSTED_STT_TOKEN', 'test-token')
    wav = tmp_path / 'test.wav'
    wav.write_bytes(wav_payload())
    body = {'error': {'code': 'NO_SPEECH'}} if api else {'detail': {'code': 'NO_SPEECH'}}
    monkeypatch.setattr(InferenceEndpoint, 'post', lambda *a, **kw: httpx.Response(422, json=body))
    args = ['--wav', str(wav)] + (['--api-url', 'http://127.0.0.1:8000'] if api else [])
    assert main(args) == 1
    output = capsys.readouterr().out
    assert 'NO_SPEECH' in output and 'UNAVAILABLE' not in output
