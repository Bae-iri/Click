"""Run with uvicorn inference.whisper_stt:app from Backend on our inference host."""
from contextlib import asynccontextmanager
import hmac
import os
from pathlib import Path
from threading import Lock

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.schemas.transcription import STT_MODEL, TranscriptionRequest, TranscriptionResponse
from app.services.audio_validation import InvalidAudio, decode_pcm_wav


def load_model():
    # Load only pre-provisioned files. No network/model downloads while handling audio.
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    model_path = os.getenv("WHISPER_MODEL_PATH", "").strip()
    model_dir = Path(model_path)
    # A missing tokenizer would otherwise trigger a Hugging Face download.
    required = ("model.bin", "config.json", "tokenizer.json", "preprocessor_config.json")
    if not model_path or not all((model_dir / name).is_file() for name in required):
        raise RuntimeError("WHISPER_MODEL_PATH must contain the complete CTranslate2 Whisper large-v3 model")
    device = os.getenv("WHISPER_DEVICE", "cuda")
    if device not in {"cuda", "cpu"}:
        raise RuntimeError("WHISPER_DEVICE must be cuda or cpu")
    compute_type = os.getenv("WHISPER_COMPUTE_TYPE", "float16" if device == "cuda" else "int8")
    # The bundled Silero VAD uses ONNX Runtime; disable its telemetry before audio processing.
    import onnxruntime
    onnxruntime.disable_telemetry_events()
    from faster_whisper import WhisperModel
    return WhisperModel(str(model_dir.resolve()), device=device, compute_type=compute_type,
        local_files_only=True, num_workers=1)


def infer(model, pcm):
    import numpy as np
    waveform = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
    segments, _ = model.transcribe(waveform, language="ko", task="transcribe",
        beam_size=5, temperature=0.0, condition_on_previous_text=False,
        vad_filter=True, without_timestamps=True, max_new_tokens=256)
    # faster-whisper performs inference lazily. Consume inside the worker lock.
    return "".join(segment.text for segment in segments).strip()


def create_app(model_loader=load_model, transcribe=infer):
    lock = Lock()

    @asynccontextmanager
    async def lifespan(app):
        token = os.getenv("SELF_HOSTED_STT_TOKEN", "").strip()
        if len(token) < 32:
            raise RuntimeError("SELF_HOSTED_STT_TOKEN must contain at least 32 characters")
        app.state.token = token
        app.state.model = model_loader()
        yield
        app.state.model = None

    app = FastAPI(title="Click private Whisper STT", lifespan=lifespan, docs_url=None, redoc_url=None)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, error):
        # Pydantic's default detail includes the rejected input (possibly raw audio).
        return JSONResponse(status_code=422, content={"detail": "Invalid audio request"})

    def authorize(authorization: str | None = Header(default=None)):
        expected = "Bearer " + app.state.token
        if not authorization or not hmac.compare_digest(authorization.encode(), expected.encode()):
            raise HTTPException(401, "Unauthorized")

    @app.get("/health")
    def health():
        return {"status": "ok", "model": STT_MODEL}

    @app.post("/v1/transcriptions", response_model=TranscriptionResponse, dependencies=[Depends(authorize)])
    def transcription(body: TranscriptionRequest):
        try:
            pcm = decode_pcm_wav(body.audio_base64, body.mime_type)
        except InvalidAudio as error:
            raise HTTPException(422, "Invalid audio request") from error
        if not lock.acquire(blocking=False):
            raise HTTPException(503, "STT busy", headers={"Retry-After": "1"})
        try:
            text = transcribe(app.state.model, pcm)
            if not isinstance(text, str) or len(text) > 4000:
                raise HTTPException(503, "Invalid STT result")
            if not text.strip():
                raise HTTPException(422, detail={"code": "NO_SPEECH"})
            return TranscriptionResponse(text=text.strip())
        except HTTPException:
            raise
        except Exception:
            # Do not emit model exceptions containing input/transcript into server logs.
            raise HTTPException(503, "STT inference failed") from None
        finally:
            lock.release()

    return app


app = create_app()
