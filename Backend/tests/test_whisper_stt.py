"""Whisper adapter checks without model downloads or real user recordings."""
import os
import struct
import sys
from types import SimpleNamespace

import numpy as np
import pytest
from fastapi.testclient import TestClient

from inference.whisper_stt import create_app, infer, load_model
from tests.test_transcription import audio_request


MODEL_FILES = ("model.bin", "config.json", "tokenizer.json", "preprocessor_config.json")
TOKEN = "whisper-test-token-" + "x" * 32


@pytest.mark.parametrize("missing", MODEL_FILES)
def test_incomplete_local_model_fails_before_loading_or_download(monkeypatch, tmp_path, missing):
    for name in MODEL_FILES:
        if name != missing:
            (tmp_path / name).write_text("{}")
    monkeypatch.setenv("WHISPER_MODEL_PATH", str(tmp_path))
    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(
        WhisperModel=lambda *a, **kw: pytest.fail("Incomplete model must not be loaded")))
    with pytest.raises(RuntimeError, match="complete CTranslate2 Whisper large-v3"):
        load_model()


@pytest.mark.parametrize("device,compute_type", [("cpu", "int8"), ("cuda", "float16")])
def test_loader_uses_only_preprovisioned_files(monkeypatch, tmp_path, device, compute_type):
    for name in MODEL_FILES:
        (tmp_path / name).write_text("{}")
    monkeypatch.setenv("WHISPER_MODEL_PATH", str(tmp_path))
    monkeypatch.setenv("WHISPER_DEVICE", device)
    monkeypatch.delenv("WHISPER_COMPUTE_TYPE", raising=False)
    for key in ("HF_HUB_OFFLINE", "TRANSFORMERS_OFFLINE", "HF_HUB_DISABLE_TELEMETRY"):
        monkeypatch.setenv(key, "0")
    telemetry_disabled = []
    monkeypatch.setitem(sys.modules, "onnxruntime", SimpleNamespace(
        disable_telemetry_events=lambda: telemetry_disabled.append(True)))
    def model(path, **kwargs):
        assert path == str(tmp_path.resolve())
        assert kwargs["local_files_only"] is True
        assert kwargs["device"] == device and kwargs["compute_type"] == compute_type
        assert os.environ["HF_HUB_OFFLINE"] == "1"
        assert os.environ["HF_HUB_DISABLE_TELEMETRY"] == "1"
        assert telemetry_disabled == [True]
        return "loaded"
    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=model))
    assert load_model() == "loaded"


def test_infer_normalizes_pcm_and_consumes_korean_segments():
    consumed = []
    def segments():
        for text in (" 글자 크기를", " 줄여줘 "):
            consumed.append(text)
            yield SimpleNamespace(text=text)
    def transcribe(waveform, **options):
        assert waveform.dtype == np.float32
        np.testing.assert_allclose(waveform, [-1.0, 0.0, 32767 / 32768])
        assert options["language"] == "ko" and options["task"] == "transcribe"
        assert options["vad_filter"] is True
        assert options["condition_on_previous_text"] is False
        return segments(), None
    assert infer(SimpleNamespace(transcribe=transcribe), struct.pack("<hhh", -32768, 0, 32767)) == "글자 크기를 줄여줘"
    assert len(consumed) == 2


def test_worker_recovers_from_silence_and_lazy_inference_failure(monkeypatch):
    monkeypatch.setenv("SELF_HOSTED_STT_TOKEN", TOKEN)
    attempts = iter(["silence", "failure", "speech"])
    def segments(outcome):
        if outcome == "failure":
            raise RuntimeError("PRIVATE_MODEL_INPUT")
        if outcome == "speech":
            yield SimpleNamespace(text=" 글자 크기 줄여줘 ")
    def transcribe(waveform, **kwargs):
        return segments(next(attempts)), None
    with TestClient(create_app(lambda: SimpleNamespace(transcribe=transcribe))) as client:
        assert client.get("/health").json()["model"] == "openai/whisper-large-v3"
        for expected in (422, 503, 200):
            response = client.post("/v1/transcriptions", json=audio_request().model_dump(),
                headers={"Authorization": "Bearer " + TOKEN})
            assert response.status_code == expected
            assert "PRIVATE_MODEL_INPUT" not in response.text
        assert response.json() == {"text": "글자 크기 줄여줘", "model": "openai/whisper-large-v3",
                                   "provider": "self_hosted"}
