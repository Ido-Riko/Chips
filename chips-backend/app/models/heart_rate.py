from sqlalchemy import Boolean, Column, ForeignKeyConstraint, Integer, String, Text
from app.database import Base


class HeartRateArchive(Base):
    __tablename__ = "heart_rate_archives"
    owner_id = Column(String(36), primary_key=True)
    id = Column(String(36), primary_key=True)
    device_id = Column(String(36), nullable=False)
    manifest = Column(Text, nullable=True)
    complete = Column(Boolean, nullable=False, default=False)
    deleted = Column(Boolean, nullable=False, default=False)


class HeartRateChunk(Base):
    __tablename__ = "heart_rate_chunks"
    owner_id = Column(String(36), primary_key=True)
    session_id = Column(String(36), primary_key=True)
    kind = Column(String(8), primary_key=True)
    offset = Column("start_index", Integer, primary_key=True)
    payload = Column(Text, nullable=False)
    items = Column(Integer, nullable=False)
    samples = Column(Integer, nullable=False)
    __table_args__ = (ForeignKeyConstraint(
        ["owner_id", "session_id"], ["heart_rate_archives.owner_id", "heart_rate_archives.id"], ondelete="CASCADE"),)
