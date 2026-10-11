"""In-memory decoder for the Android recorder's bounded PCM WAV format."""
import base64
import binascii
import io
import wave


class InvalidAudio(ValueError):
    pass


MAX_AUDIO_BYTES = 650_000
MAX_SECONDS = 20


def decode_pcm_wav(encoded: str, mime_type: str) -> bytes:
    if mime_type not in {"audio/wav", "audio/x-wav"}:
        raise InvalidAudio("Only 16 kHz mono PCM16 WAV is supported")
    try:
        audio = base64.b64decode(encoded, validate=True)
        if not audio or len(audio) > MAX_AUDIO_BYTES:
            raise InvalidAudio("Audio payload is empty or too large")
        with wave.open(io.BytesIO(audio), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (1, 2, 16000, "NONE"):
                raise InvalidAudio("Expected 16 kHz mono PCM16 WAV")
            frames = wav.getnframes()
            if not 0 < frames <= 16000 * MAX_SECONDS:
                raise InvalidAudio("Audio duration is invalid")
            pcm = wav.readframes(frames)
            if len(pcm) != frames * 2:
                raise InvalidAudio("Audio payload is truncated")
            return pcm
    except (binascii.Error, ValueError, wave.Error, EOFError) as error:
        raise InvalidAudio("Invalid PCM WAV payload") from error
