"""Independently selected local Whisper or compatible transcription API."""
import asyncio
import json
import os
import signal
from contextlib import suppress
import sys
import httpx
from fastapi import HTTPException
from app.config import settings


async def subprocess_output(*args, timeout=60):
    process = await asyncio.create_subprocess_exec(*args, stdout=asyncio.subprocess.PIPE,
                                                    stderr=asyncio.subprocess.DEVNULL, start_new_session=True)
    try:
        stdout, _ = await asyncio.wait_for(process.communicate(), timeout)
        if process.returncode:
            raise ValueError("Decoder/inference failed")
        return stdout
    finally:
        if process.returncode is None:
            with suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGKILL)
            await process.wait()


async def validate_audio(path):
    try:
        result = json.loads(await subprocess_output(
            "ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", path, timeout=10))
        streams = result["streams"]
        duration = float(result["format"]["duration"])
        if (not 0.4 <= duration <= 60 or len(streams) != 1 or
            streams[0].get("codec_name") != "aac" or streams[0].get("codec_type") != "audio" or
            "mp4" not in result["format"]["format_name"]):
            raise ValueError()
    except (ValueError, KeyError, TypeError, asyncio.TimeoutError):
        raise HTTPException(422, detail={"code": "invalid_audio"})


async def transcribe_audio(file_path: str) -> str:
    try:
        if settings.stt_provider == "local":
            result = (await subprocess_output(sys.executable, "-m", "app.services.stt_worker",
                      file_path, settings.stt_model, settings.stt_language or "")).decode().strip()
        elif settings.stt_provider == "api" and settings.stt_url:
            headers = {"Authorization": f"Bearer {settings.stt_api_key}"} if settings.stt_api_key else {}
            async with httpx.AsyncClient(timeout=60, follow_redirects=False) as client:
                with open(file_path, "rb") as audio:
                    async with client.stream("POST", settings.stt_url, headers=headers,
                        files={"file": ("recording.m4a", audio, "audio/mp4")},
                        data={"model": settings.stt_model, "response_format": "json"}) as response:
                        response.raise_for_status()
                        body = bytearray()
                        async for chunk in response.aiter_bytes():
                            body.extend(chunk)
                            if len(body) > 128 * 1024:
                                raise ValueError()
                result = json.loads(body)["text"]
        else:
            raise ValueError("Configure STT provider")
        if not isinstance(result, str) or len(result) > 10000:
            raise ValueError()
        if not result.strip():
            raise HTTPException(422, detail={"code": "no_speech"})
        return result.strip()
    except (httpx.HTTPError, OSError, ValueError, KeyError, asyncio.TimeoutError):
        raise HTTPException(503, detail={"code": "stt_unavailable"})
