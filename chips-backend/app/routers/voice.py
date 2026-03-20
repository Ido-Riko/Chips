import os
import tempfile
from fastapi import APIRouter, UploadFile, File, Depends
from fastapi.responses import PlainTextResponse
from sqlalchemy.ext.asyncio import AsyncSession
import app.services.stt as stt_service
import app.services.claude as claude_service
from app.database import get_db
from app.models.daily_log import VoiceLog

router = APIRouter()

SYSTEM_PROMPT = "You are CHIPS, a personal AI assistant. The user is speaking to you from their phone lockscreen.\nBe concise. Max 2 sentences unless detail is genuinely needed."

@router.post("/api/v1/voice-log", response_class=PlainTextResponse)
async def process_voice_log(
    audio: UploadFile = File(...),
    db: AsyncSession = Depends(get_db)
):
    with tempfile.NamedTemporaryFile(delete=False, suffix=".aac") as temp_audio:
        content = await audio.read()
        temp_audio.write(content)
        temp_path = temp_audio.name

    try:
        # Phase 0: STT Stub
        transcription = await stt_service.transcribe_audio(temp_path)

        # Call Claude
        claude_response = await claude_service.get_response(transcription, SYSTEM_PROMPT)

        # Save to DB
        voice_log = VoiceLog(
            transcription=transcription,
            response=claude_response,
            confidence=0.0
        )
        db.add(voice_log)
        await db.commit()

        return claude_response
    except Exception as e:
        # TODO: Handle error beyond basic try/catch
        raise e
    finally:
        if os.path.exists(temp_path):
            os.remove(temp_path)
