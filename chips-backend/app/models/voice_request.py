"""Durable owner-scoped identities are never expired or silently reused."""
from sqlalchemy import Column, String, Text, Integer, DateTime
from sqlalchemy.sql import func
from app.database import Base


class VoiceRequest(Base):
    __tablename__ = "voice_requests"
    owner_id = Column(String(36), primary_key=True)
    id = Column(String(36), primary_key=True)
    payload_hash = Column(String(64), nullable=False)
    state = Column(String(16), nullable=False)
    transcript = Column(Text)
    answer = Column(Text)
    error_code = Column(String(64))
    status_code = Column(Integer)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
