"""Check configured STT with a supplied test WAV, without logging audio or text."""
import argparse
import base64
import os
from pathlib import Path
import sys
from time import monotonic
from urllib.parse import urlsplit

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))

import httpx
from app.clients.self_hosted import InferenceEndpoint
from app.schemas.transcription import TranscriptionRequest, TranscriptionResponse
from app.services.audio_validation import MAX_AUDIO_BYTES, decode_pcm_wav
from app.services.transcription import (
    TranscriptionService, TranscriptionUnavailable, TranscriptionTimedOut,
    InvalidTranscriptionResponse, NoSpeechDetected,
)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, help='Load local server settings; existing environment takes precedence.')
    parser.add_argument('--wav', type=Path, help='16 kHz mono PCM16 test recording')
    parser.add_argument('--api-url', help='Backend base URL; STT_CHECK_ACCESS_TOKEN supplies an existing login token')
    parser.add_argument('--expected-text', help='Optional exact expected transcript (ignores whitespace)')
    args = parser.parse_args(argv)
    if args.env_file is not None:
        if not args.env_file.is_file():
            print('CONFIG_ERROR: env file does not exist.')
            return 2
        from dotenv import load_dotenv
        load_dotenv(args.env_file, override=False)
    if args.expected_text and not args.wav:
        parser.error('--expected-text requires --wav')
    try:
        if args.api_url:
            base = args.api_url.rstrip('/')
            parsed = urlsplit(base)
            token = os.getenv('STT_CHECK_ACCESS_TOKEN', '').strip()
            local_http = parsed.scheme == 'http' and parsed.hostname in {'localhost', '127.0.0.1', '::1'}
            if (not (parsed.scheme == 'https' or local_http) or not parsed.hostname or parsed.username
                    or parsed.password or parsed.query or parsed.fragment or not token):
                raise ValueError()
        else:
            InferenceEndpoint.from_env('SELF_HOSTED_STT')
    except ValueError:
        print('CONFIG_ERROR: set worker URL/token, or HTTPS API URL (HTTP only on loopback) and STT_CHECK_ACCESS_TOKEN.')
        return 2
    if args.wav is None:
        print('CONFIGURED_ONLY: no inference performed. Run again with --wav to verify STT.')
        return 0
    try:
        with args.wav.open('rb') as handle:
            audio = handle.read(MAX_AUDIO_BYTES + 1)
        if len(audio) > MAX_AUDIO_BYTES:
            raise ValueError()
        request = TranscriptionRequest(audio_base64=base64.b64encode(audio).decode('ascii'))
        decode_pcm_wav(request.audio_base64, request.mime_type)
    except (OSError, ValueError):
        print('AUDIO_ERROR: supply a valid 16 kHz mono PCM16 WAV, at most 20 seconds / 650000 bytes.')
        return 2
    started = monotonic()
    try:
        if args.api_url:
            endpoint = InferenceEndpoint(base + '/api/v1/speech/transcriptions', token)
            response = endpoint.post(request.model_dump(), timeout=30)
            if response.status_code != 200:
                if response.status_code == 422 and len(response.content) <= 4096:
                    try:
                        error = response.json().get('error', {})
                        if isinstance(error, dict) and error.get('code') == 'NO_SPEECH':
                            raise NoSpeechDetected()
                    except (ValueError, AttributeError):
                        pass
                print(f'HTTP_ERROR: {response.status_code} (401=login, 422=audio, 503=worker/config, 504=timeout).')
                return 1
            result = TranscriptionResponse.model_validate(response.json())
            text = result.text.strip()
            if not text or len(text) > 4000:
                raise ValueError()
        else:
            text = TranscriptionService().transcribe(request)
    except NoSpeechDetected:
        print('NO_SPEECH: no recognizable speech. Record a new WAV and retry.')
        return 1
    except (TranscriptionTimedOut, httpx.TimeoutException):
        print('TIMEOUT: inference did not respond in time.')
        return 1
    except (TranscriptionUnavailable, httpx.HTTPError):
        print('UNAVAILABLE: check worker process, endpoint and shared token.')
        return 1
    except (InvalidTranscriptionResponse, ValueError):
        print('RESPONSE_ERROR: expected nonempty self_hosted openai/whisper-large-v3 result.')
        return 1
    if args.expected_text and ''.join(text.split()) != ''.join(args.expected_text.split()):
        print('TEXT_MISMATCH: inference responded, but transcript differs from expected text.')
        return 1
    print(f'PASS: actual STT returned a valid transcript in {monotonic() - started:.2f}s. Text omitted.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
