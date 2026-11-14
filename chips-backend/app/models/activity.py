from sqlalchemy import BigInteger, Boolean, Column, String, Text, ForeignKey, UniqueConstraint
from app.database import Base


class Device(Base):
    __tablename__ = "devices"
    id = Column(String(36), primary_key=True)
    owner_id = Column(String(36), nullable=False, index=True)
    token_hash = Column(String(64), nullable=False, unique=True)
    revoked = Column(Boolean, nullable=False, default=False)


class Activity(Base):
    __tablename__ = "activities"
    owner_id = Column(String(36), primary_key=True)
    id = Column(String(36), primary_key=True)
    device_id = Column(String(36), ForeignKey("devices.id"), nullable=False)
    type = Column(String(16), nullable=False)
    occurred_at = Column(BigInteger, nullable=False)
    zone_id = Column(String(128), nullable=False)
    local_day = Column(String(10), nullable=False)
    note = Column(Text, nullable=False)
    revision = Column(BigInteger, nullable=False)
    deleted = Column(Boolean, nullable=False)
    hr_session_id = Column(String(36), nullable=True)
    __table_args__ = (UniqueConstraint("owner_id", "hr_session_id", name="activities_hr_session"),)
