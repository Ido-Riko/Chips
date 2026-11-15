from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4
from unittest.mock import AsyncMock
import pytest
from fastapi import HTTPException
from app.routers.voice import router
from app.services import stt, llm
from test_activities import api, headers


@pytest.fixture
def voice(api, monkeypatch, tmp_path):
    monkeypatch.setattr("app.routers.voice.audio_directory", lambda: tmp_path)
    api.app.include_router(router)
    monkeypatch.setattr(stt, "validate_audio", AsyncMock())
    monkeypatch.setattr(stt, "transcribe_audio", AsyncMock(return_value="What is two plus two?"))
    monkeypatch.setattr(llm, "get_response", AsyncMock(return_value="Four."))
    return api


def submit(api, key, audio=b"audio", token="owner-token"):
    return api.post("/api/v1/voice-log", headers=headers(token) | {
        "Idempotency-Key": key, "X-Chips-Voice-Protocol": "1"},
        data={"request_id": key, "protocol_version": "1"},
        files={"audio": ("clip.m4a", audio, "audio/mp4")})


def test_durable_replay_conflict_owner_and_revocation(voice):
    key = str(uuid4())
    first = submit(voice, key)
    assert first.status_code == 200, first.text
    assert first.json()["is_demo"] is False
    assert submit(voice, key).json() == first.json()
    assert submit(voice, key, b"different").status_code == 409
    assert submit(voice, key, token="revoked-token").status_code == 401
    assert submit(voice, key, token="stranger-token").status_code == 200
    assert stt.transcribe_audio.await_count == llm.get_response.await_count == 2


def test_parallel_retry_does_not_repeat_inference(voice):
    key = str(uuid4())
    with ThreadPoolExecutor(max_workers=4) as executor:
        results = list(executor.map(lambda _: submit(voice, key), range(4)))
    assert {r.status_code for r in results} <= {200, 429}
    assert submit(voice, key).status_code == 200
    assert stt.transcribe_audio.await_count == llm.get_response.await_count == 1


def test_failure_is_retained_without_implicit_provider_replay(voice):
    key = str(uuid4())
    stt.transcribe_audio.side_effect = HTTPException(503, detail={"code": "stt_unavailable"})
    for _ in range(2):
        result = submit(voice, key)
        assert result.status_code == 409
        assert result.json()["error"]["code"] == "stt_unavailable"
    assert stt.transcribe_audio.await_count == 1
    llm.get_response.assert_not_awaited()


def test_invalid_audio_does_not_infer(voice):
    stt.validate_audio.side_effect = HTTPException(422)
    assert submit(voice, str(uuid4())).status_code == 422
    stt.transcribe_audio.assert_not_awaited()


def test_no_speech_is_terminal(voice):
    stt.transcribe_audio.side_effect = HTTPException(422, detail={"code": "no_speech"})
    result = submit(voice, str(uuid4()))
    assert result.status_code == 422
    assert result.json()["error"]["code"] == "no_speech"
    llm.get_response.assert_not_awaited()


def test_real_audio_validation(tmp_path):
    import shutil
    import subprocess
    import asyncio
    if not shutil.which("ffmpeg"):
        pytest.skip("ffmpeg unavailable")
    audio = tmp_path / "clip.m4a"
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono", "-t", "1", "-c:a", "aac", str(audio)], check=True)
    asyncio.run(stt.validate_audio(str(audio)))
    audio.write_bytes(b"not audio")
    with pytest.raises(HTTPException) as error:
        asyncio.run(stt.validate_audio(str(audio)))
    assert error.value.status_code == 422


def test_explicit_api_adapters(monkeypatch, tmp_path):
    import httpx
    import asyncio
    from app.config import settings
    real_client = httpx.AsyncClient
    calls = []
    def handle(request):
        calls.append(request)
        if request.url.path == "/transcribe":
            assert b"selected-stt" in request.content
            return httpx.Response(200, json={"text": "API transcript"})
        return httpx.Response(200, json={"choices": [{"message": {"content": "API answer"}}]})
    transport = httpx.MockTransport(handle)
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: real_client(transport=transport, **kwargs))
    monkeypatch.setattr(settings, "stt_provider", "api")
    monkeypatch.setattr(settings, "stt_url", "https://example.invalid/transcribe")
    monkeypatch.setattr(settings, "stt_model", "selected-stt")
    monkeypatch.setattr(settings, "stt_api_key", "test-stt-key")
    monkeypatch.setattr(settings, "llm_url", "https://example.invalid/chat/completions")
    monkeypatch.setattr(settings, "llm_model", "selected-llm")
    monkeypatch.setattr(settings, "llm_api_key", "test-llm-key")
    path = tmp_path / "audio.m4a"; path.write_bytes(b"test")
    assert asyncio.run(stt.transcribe_audio(str(path))) == "API transcript"
    assert asyncio.run(llm.get_response("hello", "system")) == "API answer"
    assert calls[0].headers["authorization"] == "Bearer test-stt-key"
    assert calls[1].headers["authorization"] == "Bearer test-llm-key"


def test_disconnect_cancels_provider_and_keeps_terminal_identity(voice, monkeypatch):
    import asyncio
    import app.routers.voice as route
    cancelled = []
    async def slow(_):
        try:
            await asyncio.sleep(30)
        finally:
            cancelled.append(True)
    async def disconnect(_):
        await asyncio.sleep(.02)
    monkeypatch.setattr(stt, "transcribe_audio", slow)
    monkeypatch.setattr(route, "disconnect", disconnect)
    key = str(uuid4())
    response = submit(voice, key)
    assert response.status_code == 409
    assert cancelled == [True]
    assert submit(voice, key).json() == response.json()
    assert cancelled == [True]


def test_restart_marks_uncertain_work_terminal(tmp_path, monkeypatch):
    import asyncio
    from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker
    from app.database import Base
    from app.models.voice_request import VoiceRequest
    import app.main as main
    async def check():
        engine = create_async_engine(f"sqlite+aiosqlite:///{tmp_path / 'recovery.db'}")
        sessions = async_sessionmaker(engine, expire_on_commit=False)
        monkeypatch.setattr(main, "async_session_maker", sessions)
        monkeypatch.setattr("app.services.voice_audio.directory", lambda: tmp_path)
        async with engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)
        async with sessions() as db:
            db.add(VoiceRequest(owner_id="owner",id="uncertain",payload_hash="hash",state="processing"))
            db.add(VoiceRequest(owner_id="owner",id="saved",payload_hash="hash",state="complete",transcript="hello",answer="hi"))
            await db.commit()
        async with main.lifespan(main.app):
            async with sessions() as db:
                uncertain=await db.get(VoiceRequest,("owner","uncertain"))
                assert uncertain.state == "failed" and uncertain.status_code == 409
                saved=await db.get(VoiceRequest,("owner","saved"))
                assert saved.state == "complete" and saved.answer == "hi"
        await engine.dispose()
    asyncio.run(check())


def test_upload_limit_rejects_before_processing(voice):
    from app.request_limits import UploadLimit
    from fastapi.testclient import TestClient
    limited = TestClient(UploadLimit(voice.app, limit=1024), base_url="https://testserver")
    response = submit(limited, str(uuid4()), b'x' * 2048)
    assert response.status_code == 413
    stt.transcribe_audio.assert_not_awaited()


def test_stale_audio_cleanup_is_scoped(tmp_path, monkeypatch):
    from app.services import voice_audio
    monkeypatch.setattr(voice_audio, "directory", lambda: tmp_path)
    stale = tmp_path / "request-old.m4a"; stale.write_bytes(b"audio")
    unrelated = tmp_path / "other.txt"; unrelated.write_text("keep")
    voice_audio.cleanup()
    assert not stale.exists() and unrelated.exists()
