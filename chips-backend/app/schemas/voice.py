from pydantic import BaseModel
from datetime import datetime
from typing import Optional

class VoiceLogSchema(BaseModel):
    id: int
    received_at: datetime
    transcription: str
    response: str
    confidence: Optional[float] = None

    class Config:
        from_attributes = True
