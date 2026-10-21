from sqlalchemy import Column, Integer, Float, DateTime, Text, String
from sqlalchemy.sql import func
from app.database import Base

class VoiceLog(Base):
    __tablename__ = "voice_logs"

    id = Column(Integer, primary_key=True, index=True)
    owner_id = Column(String(36), nullable=True, index=True)
    received_at = Column(DateTime(timezone=True), server_default=func.now())
    transcription = Column(Text)
    response = Column(Text)
    confidence = Column(Float)
