"""Run with uvicorn inference.qwen_stt:app from Backend on our inference host."""
from contextlib import asynccontextmanager
import hmac
import os
from pathlib import Path
from threading import Lock

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.schemas.transcription import TranscriptionRequest, TranscriptionResponse
from app.services.audio_validation import InvalidAudio, decode_pcm_wav


def load_model():
    # Load only pre-provisioned files. No network/model downloads while handling audio.
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    model_dir = Path(os.environ["QWEN_ASR_MODEL_PATH"])
    if not model_dir.is_dir() or not (model_dir / "config.json").is_file():
        raise RuntimeError("QWEN_ASR_MODEL_PATH must contain the downloaded Qwen3-ASR-1.7B model")
    import torch
    from qwen_asr import Qwen3ASRModel
    device = os.getenv("QWEN_ASR_DEVICE", "cuda:0")
    dtype = torch.bfloat16 if device.startswith("cuda") and torch.cuda.is_bf16_supported() else (
        torch.float16 if device.startswith("cuda") else torch.float32)
    return Qwen3ASRModel.from_pretrained(str(model_dir.resolve()), dtype=dtype, device_map=device,
        local_files_only=True, max_inference_batch_size=1, max_new_tokens=256)


def infer(model, pcm):
    import numpy as np
    waveform = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
    results = model.transcribe(audio=(waveform, 16000), language="Korean")
    return results[0].text


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

    app = FastAPI(title="Click private Qwen STT", lifespan=lifespan, docs_url=None, redoc_url=None)

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
        return {"status": "ok", "model": "Qwen/Qwen3-ASR-1.7B"}

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
            if not isinstance(text, str) or not text.strip() or len(text) > 4000:
                raise HTTPException(422, "No usable speech")
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
