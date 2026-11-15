from fastapi import FastAPI
from contextlib import asynccontextmanager
from sqlalchemy import update
from app.database import async_session_maker
from app.models.voice_request import VoiceRequest
from app.request_limits import UploadLimit
from app.services.voice_audio import cleanup


@asynccontextmanager
async def lifespan(app):
    cleanup()
    # Exactly one API worker: interrupted requests are terminal, never inferred twice.
    async with async_session_maker() as db:
        await db.execute(update(VoiceRequest).where(VoiceRequest.state == "processing").values(
            state="failed", status_code=409, error_code="request_interrupted"))
        await db.commit()
    yield

from app.routers import voice, health, activities, beat_saber, drawings, pantry, heart_rate

app = FastAPI(title="CHIPS Personal Server", lifespan=lifespan)
app.add_middleware(UploadLimit)

app.include_router(activities.router)
app.include_router(beat_saber.router)
app.include_router(drawings.router)
app.include_router(pantry.router)
app.include_router(heart_rate.router)
app.include_router(voice.router)
app.include_router(health.router)

@app.get("/")
def read_root():
    return {"message": "CHIPS Personal Server is running."}
